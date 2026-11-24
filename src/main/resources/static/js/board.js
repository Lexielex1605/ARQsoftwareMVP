/**
 * Tablero local de 10 casillas apilables para ordenar cartas en mano.
 * Opera 100% en cliente (clic/toque y drag-and-drop) sin modificar el estado del servidor
 * hasta que el jugador decida descartar o declarar cierre.
 */
(function (global) {
    "use strict";

    const SLOT_COUNT = 10;
    const SUIT_ORDER = { SPADES: 0, HEARTS: 1, DIAMONDS: 2, CLUBS: 3, JOKER: 4 };
    const RANK_ORDER = {
        ACE: 1, TWO: 2, THREE: 3, FOUR: 4, FIVE: 5, SIX: 6, SEVEN: 7,
        EIGHT: 8, NINE: 9, TEN: 10, JACK: 11, QUEEN: 12, KING: 13, JOKER: 99
    };

    const SUIT_FOLDERS = {
        SPADES: "spades",
        HEARTS: "hearts",
        DIAMONDS: "diamonds",
        CLUBS: "clubs",
        JOKER: "jokers"
    };

    const RANK_FILES = {
        ACE: "A", TWO: "02", THREE: "03", FOUR: "04", FIVE: "05", SIX: "06",
        SEVEN: "07", EIGHT: "08", NINE: "09", TEN: "10", JACK: "J", QUEEN: "Q", KING: "K"
    };

    function resolveCardImage(card) {
        if (!card) return "";
        if (card.imagePath) return card.imagePath;
        if (card.suit === "JOKER" || card.rank === "JOKER") {
            const isBlack = String(card.id || "").toUpperCase().includes("BLACK");
            return `/assets/cards/jokers/${isBlack ? "card_joker_black.png" : "card_joker_red.png"}`;
        }
        const folder = SUIT_FOLDERS[card.suit] || "spades";
        const code = RANK_FILES[card.rank] || "A";
        return `/assets/cards/${folder}/card_${folder}_${code}.png`;
    }

    function resolveBackImage(back) {
        return back === "BLUE"
            ? "/assets/cards/cardback/cardback_blue.png"
            : "/assets/cards/cardback/cardback_red.png";
    }

    class CardBoard {
        constructor(containerEl, onSelectionChange) {
            this.containerEl = containerEl;
            this.onSelectionChange = onSelectionChange;
            this.slots = Array.from({ length: SLOT_COUNT }, () => []);
            this.cardsById = new Map();
            this.selectedCardId = null;
            this.draggedCardId = null;
        }

        getSelectedCardId() {
            return this.selectedCardId;
        }

        clearSelection() {
            this.selectedCardId = null;
            this.notifySelection();
            this.render();
        }

        syncHand(handCards) {
            const incoming = Array.isArray(handCards) ? handCards : [];
            const incomingIds = new Set(incoming.map((c) => c.id));
            this.cardsById = new Map(incoming.map((c) => [c.id, c]));

            // Eliminar cartas ya descartadas conservando el orden local actual
            const placed = new Set();
            for (let i = 0; i < SLOT_COUNT; i++) {
                this.slots[i] = this.slots[i].filter((id) => {
                    const keep = incomingIds.has(id);
                    if (keep) placed.add(id);
                    return keep;
                });
            }

            // Ubicar cartas nuevas en la primera casilla vacía (o en la última si todas están ocupadas)
            for (const card of incoming) {
                if (!placed.has(card.id)) {
                    const emptySlot = this.slots.findIndex((s) => s.length === 0);
                    const targetSlot = emptySlot >= 0 ? emptySlot : SLOT_COUNT - 1;
                    this.slots[targetSlot].push(card.id);
                    placed.add(card.id);
                }
            }

            if (this.selectedCardId && !incomingIds.has(this.selectedCardId)) {
                this.selectedCardId = null;
                this.notifySelection();
            }
            this.render();
        }

        moveCardToSlot(cardId, targetSlotIndex) {
            if (!this.cardsById.has(cardId) || targetSlotIndex < 0 || targetSlotIndex >= SLOT_COUNT) {
                return;
            }
            for (let i = 0; i < SLOT_COUNT; i++) {
                const idx = this.slots[i].indexOf(cardId);
                if (idx >= 0) {
                    this.slots[i].splice(idx, 1);
                    break;
                }
            }
            this.slots[targetSlotIndex].push(cardId);
            this.selectedCardId = null;
            this.notifySelection();
            this.render();
        }

        sortBySuit() {
            const sorted = Array.from(this.cardsById.values()).sort((a, b) => {
                const suitDiff = (SUIT_ORDER[a.suit] ?? 9) - (SUIT_ORDER[b.suit] ?? 9);
                return suitDiff !== 0 ? suitDiff : (RANK_ORDER[a.rank] ?? 99) - (RANK_ORDER[b.rank] ?? 99);
            });
            this.distributeCards(sorted);
        }

        sortByRank() {
            const sorted = Array.from(this.cardsById.values()).sort((a, b) => {
                const rankDiff = (RANK_ORDER[a.rank] ?? 99) - (RANK_ORDER[b.rank] ?? 99);
                return rankDiff !== 0 ? rankDiff : (SUIT_ORDER[a.suit] ?? 9) - (SUIT_ORDER[b.suit] ?? 9);
            });
            this.distributeCards(sorted);
        }

        spreadAcrossSlots() {
            const orderedIds = this.slots.flat();
            const cards = orderedIds.map((id) => this.cardsById.get(id)).filter(Boolean);
            this.distributeCards(cards);
        }

        distributeCards(cards) {
            this.slots = Array.from({ length: SLOT_COUNT }, () => []);
            cards.forEach((card, index) => {
                const targetSlot = Math.min(index, SLOT_COUNT - 1);
                this.slots[targetSlot].push(card.id);
            });
            this.render();
        }

        render() {
            if (!this.containerEl) return;
            this.containerEl.innerHTML = "";

            for (let slotIndex = 0; slotIndex < SLOT_COUNT; slotIndex++) {
                const slotEl = this.createSlotElement(slotIndex);
                this.containerEl.appendChild(slotEl);
            }
        }

        createSlotElement(slotIndex) {
            const slotEl = document.createElement("div");
            slotEl.className = "board-slot" + (this.selectedCardId ? " slot-targetable" : "");
            slotEl.dataset.slotIndex = String(slotIndex);

            const label = document.createElement("span");
            label.className = "slot-index-label";
            label.textContent = String(slotIndex + 1);
            slotEl.appendChild(label);

            const cardIds = this.slots[slotIndex];
            const stackEl = document.createElement("div");
            stackEl.className = "slot-stack";
            if (cardIds.length > 1) {
                const extraPx = (cardIds.length - 1) * 26;
                stackEl.style.minHeight = `calc(var(--card-h) + ${extraPx}px)`;
            }

            cardIds.forEach((cardId, stackPos) => {
                const card = this.cardsById.get(cardId);
                if (!card) return;
                stackEl.appendChild(this.createCardElement(card, stackPos));
            });

            slotEl.appendChild(stackEl);
            this.bindSlotEvents(slotEl, slotIndex);
            return slotEl;
        }

        createCardElement(card, stackPos) {
            const cardEl = document.createElement("div");
            cardEl.className = "playing-card" + (this.selectedCardId === card.id ? " selected" : "");
            cardEl.style.setProperty("--stack-index", String(stackPos));
            cardEl.style.zIndex = String(stackPos + 1);
            cardEl.draggable = true;
            cardEl.dataset.cardId = card.id;
            cardEl.title = `${card.rank} de ${card.suit}`;

            const img = document.createElement("img");
            img.src = resolveCardImage(card);
            img.alt = `${card.rank} ${card.suit}`;
            cardEl.appendChild(img);

            cardEl.addEventListener("click", (evt) => {
                evt.stopPropagation();
                if (this.selectedCardId === card.id) {
                    this.selectedCardId = null;
                } else {
                    this.selectedCardId = card.id;
                }
                this.notifySelection();
                this.render();
            });

            cardEl.addEventListener("dragstart", (evt) => {
                this.draggedCardId = card.id;
                evt.dataTransfer.setData("text/plain", card.id);
                evt.dataTransfer.effectAllowed = "move";
            });

            cardEl.addEventListener("dragend", () => {
                this.draggedCardId = null;
            });

            return cardEl;
        }

        bindSlotEvents(slotEl, slotIndex) {
            slotEl.addEventListener("click", () => {
                if (this.selectedCardId) {
                    this.moveCardToSlot(this.selectedCardId, slotIndex);
                }
            });

            slotEl.addEventListener("dragover", (evt) => {
                evt.preventDefault();
                slotEl.classList.add("drag-over");
            });

            slotEl.addEventListener("dragleave", () => {
                slotEl.classList.remove("drag-over");
            });

            slotEl.addEventListener("drop", (evt) => {
                evt.preventDefault();
                slotEl.classList.remove("drag-over");
                const droppedId = evt.dataTransfer.getData("text/plain") || this.draggedCardId;
                if (droppedId) {
                    this.moveCardToSlot(droppedId, slotIndex);
                }
            });
        }

        notifySelection() {
            if (typeof this.onSelectionChange === "function") {
                this.onSelectionChange(this.selectedCardId);
            }
        }
    }

    global.CardAssets = { resolveCardImage, resolveBackImage };
    global.CardBoard = CardBoard;
})(window);

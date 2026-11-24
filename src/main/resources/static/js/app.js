/**
 * Cliente HTTP + WebSocket para Clásica & Apuntado 101.
 * Maneja Lobby, conexión en tiempo real, reconexión automática vía localStorage y render de mesa.
 */
(function () {
    "use strict";

    const STORAGE_KEY = "juego_cartas_session_v1";

    const state = {
        session: null,      // { roomCode, matchId, playerId, playerName, sessionToken, host }
        gameView: null,     // PlayerGameView recibido del servidor
        ws: null,
        reconnectTimer: null,
        intentionalClose: false,
        lastSummaryRound: 0,
        pendingCloseCardId: null
    };

    const el = {
        toastContainer: document.getElementById("toast-container"),
        lobbyScreen: document.getElementById("lobby-screen"),
        gameScreen: document.getElementById("game-screen"),
        reconnectBanner: document.getElementById("reconnect-banner"),
        reconnectDetails: document.getElementById("reconnect-details"),
        btnReconnectSaved: document.getElementById("btn-reconnect-saved"),
        btnClearSaved: document.getElementById("btn-clear-saved"),

        createForm: document.getElementById("create-room-form"),
        createHostName: document.getElementById("create-host-name"),
        cfgMode: document.getElementById("cfg-mode"),
        cfgElimination: document.getElementById("cfg-elimination"),
        cfgMaxPlayers: document.getElementById("cfg-max-players"),
        cfgDeckCount: document.getElementById("cfg-deck-count"),
        cfgValuePolicy: document.getElementById("cfg-value-policy"),
        cfgCyclePolicy: document.getElementById("cfg-cycle-policy"),
        cfgJokers: document.getElementById("cfg-jokers"),
        deckHint: document.getElementById("deck-hint"),
        apuntadoLimitGroup: document.getElementById("apuntado-limit-group"),
        cfgApuntadoLimit: document.getElementById("cfg-apuntado-limit"),
        apuntadoLimitHint: document.getElementById("apuntado-limit-hint"),

        joinForm: document.getElementById("join-room-form"),
        joinPlayerName: document.getElementById("join-player-name"),
        joinRoomCode: document.getElementById("join-room-code"),
        roomPreview: document.getElementById("room-preview"),

        hudRoomCode: document.getElementById("hud-room-code"),
        hudModeBadge: document.getElementById("hud-mode-badge"),
        hudRoundBadge: document.getElementById("hud-round-badge"),
        hudLimitBadge: document.getElementById("hud-limit-badge"),
        wsStatus: document.getElementById("ws-status"),
        btnCopyCode: document.getElementById("btn-copy-code"),
        btnLeaveRoom: document.getElementById("btn-leave-room"),

        waitingPanel: document.getElementById("waiting-panel"),
        waitingRoomCode: document.getElementById("waiting-room-code"),
        waitingConfigSummary: document.getElementById("waiting-config-summary"),
        waitingPlayersList: document.getElementById("waiting-players-list"),
        btnStartGame: document.getElementById("btn-start-game"),
        waitingHint: document.getElementById("waiting-hint"),

        activeTable: document.getElementById("active-table"),
        opponentsArea: document.getElementById("opponents-area"),
        deckCount: document.getElementById("deck-count"),
        deckTopImg: document.getElementById("deck-top-img"),
        btnDrawDeck: document.getElementById("btn-draw-deck"),
        btnDrawDiscard: document.getElementById("btn-draw-discard"),
        discardTopImg: document.getElementById("discard-top-img"),
        discardEmptyLabel: document.getElementById("discard-empty-label"),
        turnBanner: document.getElementById("turn-banner"),
        btnDiscard: document.getElementById("btn-discard"),
        btnDeclareClose: document.getElementById("btn-declare-close"),

        handCount: document.getElementById("hand-count"),
        handBoard: document.getElementById("hand-board"),
        btnSortSuit: document.getElementById("btn-sort-suit"),
        btnSortRank: document.getElementById("btn-sort-rank"),
        btnSpreadSlots: document.getElementById("btn-spread-slots"),

        summaryModal: document.getElementById("summary-modal"),
        summaryTitle: document.getElementById("summary-title"),
        summarySubtitle: document.getElementById("summary-subtitle"),
        summaryTableBody: document.getElementById("summary-table-body"),
        btnCloseSummary: document.getElementById("btn-close-summary"),

        closeModeModal: document.getElementById("close-mode-modal"),
        btnCloseAsClassic: document.getElementById("btn-close-as-classic"),
        btnCloseAsApuntado: document.getElementById("btn-close-as-apuntado"),
        closeAsApuntadoDesc: document.getElementById("close-as-apuntado-desc"),
        btnCancelCloseMode: document.getElementById("btn-cancel-close-mode")
    };

    const board = new window.CardBoard(el.handBoard, updateActionButtons);

    function init() {
        bindLobbyEvents();
        bindGameEvents();
        updateDeckHint();
        updateApuntadoLimitUi();
        checkSavedSession();
    }

    function showToast(message, type = "info") {
        const toast = document.createElement("div");
        toast.className = `toast ${type === "error" ? "toast-error" : type === "success" ? "toast-success" : ""}`;
        toast.textContent = message;
        el.toastContainer.appendChild(toast);
        setTimeout(() => toast.remove(), 4200);
    }

    async function apiJson(url, options = {}) {
        const response = await fetch(url, {
            headers: { "Content-Type": "application/json" },
            ...options
        });
        const data = await response.json().catch(() => ({}));
        if (!response.ok) {
            throw new Error(data.error || `Error HTTP ${response.status}`);
        }
        return data;
    }

    /* ==================== SESIÓN EN LOCALSTORAGE ==================== */
    function saveSession(ticket) {
        state.session = {
            roomCode: ticket.roomCode,
            matchId: ticket.matchId,
            playerId: ticket.playerId,
            playerName: ticket.playerName,
            sessionToken: ticket.sessionToken,
            host: Boolean(ticket.host)
        };
        localStorage.setItem(STORAGE_KEY, JSON.stringify(state.session));
    }

    function loadSavedSession() {
        try {
            const raw = localStorage.getItem(STORAGE_KEY);
            return raw ? JSON.parse(raw) : null;
        } catch {
            return null;
        }
    }

    function clearSavedSession() {
        localStorage.removeItem(STORAGE_KEY);
        el.reconnectBanner.classList.add("hidden");
    }

    function checkSavedSession() {
        const saved = loadSavedSession();
        if (!saved || !saved.roomCode || !saved.playerId) return;
        el.reconnectDetails.textContent = `Sala ${saved.roomCode} · ${saved.playerName || "Jugador"}`;
        el.reconnectBanner.classList.remove("hidden");
    }

    /* ==================== EVENTOS DE LOBBY ==================== */
    function bindLobbyEvents() {
        el.cfgMaxPlayers.addEventListener("input", updateDeckHint);
        el.cfgDeckCount.addEventListener("input", updateDeckHint);
        el.cfgMode.addEventListener("change", updateApuntadoLimitUi);
        el.cfgApuntadoLimit.addEventListener("input", updateApuntadoLimitUi);

        el.joinRoomCode.addEventListener("input", async () => {
            el.joinRoomCode.value = el.joinRoomCode.value.toUpperCase().replace(/[^A-Z0-9]/g, "");
            if (el.joinRoomCode.value.length === 6) {
                await previewRoom(el.joinRoomCode.value);
            } else {
                el.roomPreview.classList.add("hidden");
            }
        });

        el.createForm.addEventListener("submit", async (evt) => {
            evt.preventDefault();
            try {
                const payload = {
                    hostName: el.createHostName.value.trim(),
                    gameMode: el.cfgMode.value,
                    maxPlayers: Number(el.cfgMaxPlayers.value),
                    deckCount: Number(el.cfgDeckCount.value),
                    includeJokers: el.cfgJokers.checked,
                    cardValuePolicy: el.cfgValuePolicy.value,
                    cyclePolicy: el.cfgCyclePolicy.value,
                    eliminationLimit: Number(el.cfgElimination.value),
                    apuntadoCloseLimit: Number(el.cfgApuntadoLimit.value)
                };
                const ticket = await apiJson("/api/rooms", {
                    method: "POST",
                    body: JSON.stringify(payload)
                });
                enterRoom(ticket, false);
            } catch (err) {
                showToast(err.message, "error");
            }
        });

        el.joinForm.addEventListener("submit", async (evt) => {
            evt.preventDefault();
            try {
                const code = el.joinRoomCode.value.trim().toUpperCase();
                const ticket = await apiJson(`/api/rooms/${encodeURIComponent(code)}/join`, {
                    method: "POST",
                    body: JSON.stringify({ playerName: el.joinPlayerName.value.trim() })
                });
                enterRoom(ticket, false);
            } catch (err) {
                showToast(err.message, "error");
            }
        });

        el.btnReconnectSaved.addEventListener("click", async () => {
            const saved = loadSavedSession();
            if (!saved) return;
            try {
                const ticket = await apiJson(`/api/rooms/${encodeURIComponent(saved.roomCode)}/reconnect`, {
                    method: "POST",
                    body: JSON.stringify({
                        sessionToken: saved.sessionToken,
                        playerId: saved.playerId
                    })
                });
                enterRoom(ticket, true);
            } catch (err) {
                showToast(err.message, "error");
                clearSavedSession();
            }
        });

        el.btnClearSaved.addEventListener("click", clearSavedSession);
    }

    function updateDeckHint() {
        const players = Math.max(2, Math.min(16, Number(el.cfgMaxPlayers.value) || 4));
        const minDecks = Math.ceil(players / 4);
        if (Number(el.cfgDeckCount.value) < minDecks) {
            el.cfgDeckCount.value = String(minDecks);
        }
        el.cfgDeckCount.min = String(minDecks);
        el.deckHint.textContent = `Para ${players} jugadores se requiere mínimo ${minDecks} baraja(s) (máx. 4 por baraja; impares: reverso rojo, pares: azul).`;
    }

    function updateApuntadoLimitUi() {
        const mode = el.cfgMode.value;
        const show = mode === "APUNTADO" || mode === "MIXED";
        el.apuntadoLimitGroup.classList.toggle("hidden", !show);
        const limit = Math.max(2, Math.min(26, Number(el.cfgApuntadoLimit.value) || 5));
        el.apuntadoLimitHint.textContent =
            `Solo cierre 3-3-3 + 1 sobrante menor a ${limit} (1 a ${limit - 1} pts; se suman a quien cierra).`;
    }

    function formatGameMode(mode) {
        if (mode === "APUNTADO") return "Apuntado 101";
        if (mode === "MIXED") return "Mixto";
        return "Clásica (Regular)";
    }

    async function previewRoom(roomCode) {
        try {
            const info = await apiJson(`/api/rooms/${encodeURIComponent(roomCode)}`);
            const modeLabel = formatGameMode(info.config.gameMode);
            el.roomPreview.textContent =
                `Sala válida · ${modeLabel} · ${info.playerCount}/${info.maxPlayers} jugadores · Estado: ${info.status}`;
            el.roomPreview.classList.remove("hidden");
        } catch (err) {
            el.roomPreview.textContent = err.message;
            el.roomPreview.classList.remove("hidden");
        }
    }

    /* ==================== EVENTOS DE MESA Y WEBSOCKET ==================== */
    function bindGameEvents() {
        el.btnCopyCode.addEventListener("click", () => {
            if (!state.session) return;
            copyTextSafe(state.session.roomCode);
            showToast(`Código ${state.session.roomCode} copiado`, "success");
        });

        el.btnLeaveRoom.addEventListener("click", async () => {
            const view = state.gameView;
            if (view && view.matchStatus === "EN_CURSO") {
                const ok = window.confirm("¿Seguro que deseas abandonar la partida? No podrás volver a unirte.");
                if (!ok) return;
            }
            if (state.session && state.ws && state.ws.readyState === WebSocket.OPEN) {
                state.intentionalClose = true;
                sendWs({ type: "LEAVE_ROOM" });
                setTimeout(() => finalizeLocalLeave(), 250);
                return;
            }
            if (state.session) {
                try {
                    await apiJson(`/api/rooms/${encodeURIComponent(state.session.roomCode)}/leave`, {
                        method: "POST",
                        body: JSON.stringify({ playerId: state.session.playerId })
                    });
                } catch {
                    // Si la sala ya no existe o cerró, igual limpiamos la sesión local
                }
            }
            finalizeLocalLeave();
        });

        el.btnStartGame.addEventListener("click", () => sendWs({ type: "START_GAME" }));
        el.btnDrawDeck.addEventListener("click", () => sendWs({ type: "DRAW_DECK" }));
        el.btnDrawDiscard.addEventListener("click", () => sendWs({ type: "DRAW_DISCARD" }));

        el.btnDiscard.addEventListener("click", () => {
            const cardId = board.getSelectedCardId();
            if (!cardId) {
                showToast("Selecciona una carta de tu mano para descartar", "error");
                return;
            }
            if (state.gameView && state.gameView.forbiddenDiscardCardId === cardId) {
                showToast("No puedes descartar la misma carta que acabas de tomar del pozo", "error");
                return;
            }
            sendWs({ type: "DISCARD", cardId });
            board.clearSelection();
        });

        el.btnDeclareClose.addEventListener("click", () => {
            const view = state.gameView;
            if (!view) return;
            if (!isMyPhase("MUST_DRAW")) {
                showToast("No puedes declarar cierre después de robar; descarta y cierra en tu próximo turno", "error");
                return;
            }
            if (view.config.gameMode === "MIXED") {
                state.pendingCloseCardId = null;
                const limit = view.config.apuntadoCloseLimit ?? 5;
                if (el.closeAsApuntadoDesc) {
                    el.closeAsApuntadoDesc.textContent =
                        `3 ternas armadas + sobrante menor a ${limit} pts (se suman los puntos de la sobrante a quien cierra).`;
                }
                el.closeModeModal.classList.remove("hidden");
                return;
            }
            sendWs({ type: "DECLARE_CLOSE", cardId: null });
        });

        el.btnCloseAsClassic.addEventListener("click", () => submitMixedClose("CLASSIC"));
        el.btnCloseAsApuntado.addEventListener("click", () => submitMixedClose("APUNTADO"));
        el.btnCancelCloseMode.addEventListener("click", () => {
            state.pendingCloseCardId = null;
            el.closeModeModal.classList.add("hidden");
        });

        // Permitir arrastrar carta directamente al pozo de descarte durante MUST_DISCARD
        el.btnDrawDiscard.addEventListener("dragover", (evt) => {
            if (isMyPhase("MUST_DISCARD")) {
                evt.preventDefault();
                el.btnDrawDiscard.classList.add("drag-over");
            }
        });
        el.btnDrawDiscard.addEventListener("dragleave", () => {
            el.btnDrawDiscard.classList.remove("drag-over");
        });
        el.btnDrawDiscard.addEventListener("drop", (evt) => {
            evt.preventDefault();
            el.btnDrawDiscard.classList.remove("drag-over");
            if (!isMyPhase("MUST_DISCARD")) return;
            const droppedCardId = evt.dataTransfer.getData("text/plain");
            if (!droppedCardId) return;
            if (state.gameView && state.gameView.forbiddenDiscardCardId === droppedCardId) {
                showToast("No puedes descartar la misma carta que acabas de tomar del pozo", "error");
                return;
            }
            sendWs({ type: "DISCARD", cardId: droppedCardId });
            board.clearSelection();
        });

        el.btnSortSuit.addEventListener("click", () => board.sortBySuit());
        el.btnSortRank.addEventListener("click", () => board.sortByRank());
        el.btnSpreadSlots.addEventListener("click", () => board.spreadAcrossSlots());
        el.btnCloseSummary.addEventListener("click", () => el.summaryModal.classList.add("hidden"));
    }

    function finalizeLocalLeave() {
        if (!state.session && !state.ws) return;
        state.intentionalClose = true;
        if (state.reconnectTimer) clearTimeout(state.reconnectTimer);
        if (state.ws) {
            try { state.ws.close(); } catch { /* ignore */ }
        }
        state.ws = null;
        state.gameView = null;
        state.session = null;
        state.pendingCloseCardId = null;
        clearSavedSession();
        el.closeModeModal.classList.add("hidden");
        el.summaryModal.classList.add("hidden");
        el.gameScreen.classList.add("hidden");
        el.lobbyScreen.classList.remove("hidden");
    }

    function submitMixedClose(closeMode) {
        const cardId = state.pendingCloseCardId;
        state.pendingCloseCardId = null;
        el.closeModeModal.classList.add("hidden");
        sendWs({ type: "DECLARE_CLOSE", cardId, closeMode });
    }

    function enterRoom(ticket, isReconnect) {
        saveSession(ticket);
        state.intentionalClose = false;
        state.lastSummaryRound = 0;

        el.lobbyScreen.classList.add("hidden");
        el.gameScreen.classList.remove("hidden");
        el.hudRoomCode.textContent = ticket.roomCode;
        el.waitingRoomCode.textContent = ticket.roomCode;

        connectWebSocket(isReconnect ? "RECONNECT" : "JOIN");
    }

    function connectWebSocket(handshakeType) {
        if (!state.session) return;
        const protocol = window.location.protocol === "https:" ? "wss:" : "ws:";
        const wsUrl = `${protocol}//${window.location.host}/ws/${encodeURIComponent(state.session.roomCode)}`;
        const socket = new WebSocket(wsUrl);
        state.ws = socket;

        socket.addEventListener("open", () => {
            setWsStatus(true);
            sendWs({
                type: handshakeType,
                playerId: state.session.playerId,
                sessionToken: state.session.sessionToken
            });
        });

        socket.addEventListener("message", (evt) => {
            const msg = JSON.parse(evt.data);
            handleServerEvent(msg);
        });

        socket.addEventListener("close", () => {
            setWsStatus(false);
            if (!state.intentionalClose && state.session) {
                state.reconnectTimer = setTimeout(() => connectWebSocket("RECONNECT"), 2200);
            }
        });

        socket.addEventListener("error", () => {
            setWsStatus(false);
        });
    }

    function sendWs(payload) {
        if (!state.ws || state.ws.readyState !== WebSocket.OPEN) {
            showToast("Sin conexión WebSocket activa", "error");
            return;
        }
        state.ws.send(JSON.stringify({
            playerId: state.session ? state.session.playerId : null,
            ...payload
        }));
    }

    function setWsStatus(online) {
        el.wsStatus.textContent = online ? "● Conectado" : "● Reconectando...";
        el.wsStatus.className = `ws-status ${online ? "ws-online" : "ws-offline"}`;
    }

    function handleServerEvent(msg) {
        if (msg.type === "ERROR") {
            showToast(msg.error || "Acción rechazada", "error");
            return;
        }
        if (msg.type === "JOINED" || msg.type === "RECONNECTED") {
            if (msg.payload) saveSession(msg.payload);
            return;
        }
        if (msg.type === "LEFT_ROOM") {
            finalizeLocalLeave();
            showToast("Has abandonado la partida", "info");
            return;
        }
        if (msg.type === "PLAYER_LEFT" && msg.payload) {
            showToast(msg.payload.message || "Un jugador abandonó la partida",
                msg.payload.matchFinished ? "success" : "error");
            if (msg.payload.victorySummary) {
                renderRoundSummaryModal(msg.payload.victorySummary);
            }
            return;
        }
        if (msg.type === "ROUND_CLOSED" && msg.payload) {
            renderRoundSummaryModal(msg.payload);
            return;
        }
        if (msg.type === "GAME_STATE" && msg.payload) {
            state.gameView = msg.payload;
            renderGameState(msg.payload);
        }
    }

    /* ==================== RENDER DE ESTADO ==================== */
    function renderGameState(view) {
        const modeText = formatGameMode(view.config.gameMode);
        el.hudRoomCode.textContent = view.roomCode;
        el.hudModeBadge.textContent = modeText;
        el.hudRoundBadge.textContent = `Ronda ${view.roundNumber}`;
        el.hudLimitBadge.textContent = `Límite ${view.config.eliminationLimit.replace("LIMIT_", "")}`;

        if (view.matchStatus === "ESPERANDO") {
            el.waitingPanel.classList.remove("hidden");
            el.activeTable.classList.add("hidden");
            renderWaitingRoom(view);
            return;
        }

        el.waitingPanel.classList.add("hidden");
        el.activeTable.classList.remove("hidden");

        renderPlayers(view);
        renderCenterPiles(view);
        el.handCount.textContent = String((view.viewerHand || []).length);
        board.syncHand(view.viewerHand || []);
        updateActionButtons();

        if (view.lastRoundSummary && view.lastRoundSummary.roundNumber > state.lastSummaryRound) {
            renderRoundSummaryModal(view.lastRoundSummary);
        }
    }

    function renderWaitingRoom(view) {
        el.waitingRoomCode.textContent = view.roomCode;
        el.waitingConfigSummary.innerHTML = "";
        const pills = [
            formatGameMode(view.config.gameMode),
            `${view.config.deckCount} baraja(s)`,
            view.config.includeJokers ? "Con Jokers" : "Sin Jokers",
            `Ciclo: ${view.config.cyclePolicy}`,
            `Límite: ${view.config.eliminationLimit.replace("LIMIT_", "")} pts`
        ];
        if (view.config.gameMode === "APUNTADO" || view.config.gameMode === "MIXED") {
            pills.push(`Cierre Apuntado: < ${view.config.apuntadoCloseLimit ?? 5} pts`);
        }
        pills.forEach((txt) => {
            const span = document.createElement("span");
            span.className = "badge badge-muted";
            span.textContent = txt;
            el.waitingConfigSummary.appendChild(span);
        });

        el.waitingPlayersList.innerHTML = "";
        view.players.forEach((p) => {
            const row = document.createElement("div");
            row.className = "waiting-player-row";
            const statusText = p.status === "ABANDONADO"
                ? `<span class="status-pill status-eliminated">ABANDONÓ</span>`
                : `<span class="${p.connected ? "ws-online" : "ws-offline"}">${p.connected ? "● En línea" : "● Desconectado"}</span>`;
            row.innerHTML = `
                <span>${escapeHtml(p.name)} ${p.host ? "👑" : ""} ${p.playerId === state.session.playerId ? "(Tú)" : ""}</span>
                ${statusText}
            `;
            el.waitingPlayersList.appendChild(row);
        });

        const activeWaitingCount = view.players.filter((p) => p.status === "ACTIVO").length;
        const amHost = view.players.some((p) => p.playerId === state.session.playerId && p.host && p.status === "ACTIVO");
        const canStart = activeWaitingCount >= 2;
        el.btnStartGame.classList.toggle("hidden", !amHost);
        el.btnStartGame.disabled = !canStart;
        el.waitingHint.textContent = canStart
            ? (amHost ? "Todo listo. Presiona Iniciar Partida cuando estén todos." : "Esperando a que el anfitrión inicie la partida...")
            : `Esperando más jugadores (${activeWaitingCount}/${view.config.maxPlayers})...`;
    }

    function renderPlayers(view) {
        el.opponentsArea.innerHTML = "";
        const limitPts = view.config.eliminationLimit.replace("LIMIT_", "");

        view.players.forEach((p) => {
            const isTurn = view.currentTurnPlayerId === p.playerId && view.matchStatus === "EN_CURSO";
            const isMe = p.playerId === state.session.playerId;
            const inactive = p.status !== "ACTIVO";
            const statusLabel = p.status === "ABANDONADO" ? "ABANDONÓ" : p.status;

            const card = document.createElement("div");
            card.className = `opponent-card${isTurn ? " is-turn" : ""}${inactive ? " is-eliminated" : ""}`;

            const backsHtml = (p.cardBacks || []).map((b) =>
                `<img class="mini-back-img" src="${window.CardAssets.resolveBackImage(b)}" alt="Reverso ${b}">`
            ).join("");

            card.innerHTML = `
                <div class="opponent-header">
                    <strong>${escapeHtml(p.name)}${isMe ? " (Tú)" : ""}${p.host ? " 👑" : ""}</strong>
                    <span class="status-pill ${inactive ? "status-eliminated" : "status-active"}">${statusLabel}</span>
                </div>
                <div class="opponent-meta">
                    <span>Puntos: <strong>${p.accumulatedScore}</strong> / ${limitPts}</span>
                    <span>${p.connected ? "●" : "○"} ${p.cardCount} cartas</span>
                </div>
                <div class="mini-backs">${backsHtml}</div>
            `;
            el.opponentsArea.appendChild(card);
        });
    }

    function renderCenterPiles(view) {
        el.deckCount.textContent = String(view.remainingDeckSize);
        el.deckTopImg.src = window.CardAssets.resolveBackImage(view.topDeckBack || "RED");

        if (view.topDiscardCard) {
            el.discardTopImg.src = window.CardAssets.resolveCardImage(view.topDiscardCard);
            el.discardTopImg.classList.remove("hidden");
            el.discardEmptyLabel.classList.add("hidden");
        } else {
            el.discardTopImg.classList.add("hidden");
            el.discardEmptyLabel.classList.remove("hidden");
        }

        const isMyTurn = view.currentTurnPlayerId === state.session.playerId && view.matchStatus === "EN_CURSO";
        el.turnBanner.classList.toggle("my-turn", isMyTurn);

        if (view.matchStatus === "FINALIZADA") {
            const winner = view.players.find((p) => p.playerId === view.winnerPlayerId);
            el.turnBanner.textContent = `🏆 Partida finalizada · Ganador: ${winner ? winner.name : "N/A"}`;
            return;
        }
        if (isMyTurn) {
            el.turnBanner.textContent = view.turnPhase === "MUST_DRAW"
                ? "¡Tu turno! Roba del mazo/pozo o declara cierre"
                : "¡Tu turno! Selecciona una carta para descartar";
        } else {
            const active = view.players.find((p) => p.playerId === view.currentTurnPlayerId);
            el.turnBanner.textContent = `Turno de ${active ? active.name : "..."}`;
        }
    }

    function isMyPhase(expectedPhase) {
        const view = state.gameView;
        return Boolean(
            view &&
            state.session &&
            view.matchStatus === "EN_CURSO" &&
            view.currentTurnPlayerId === state.session.playerId &&
            view.turnPhase === expectedPhase
        );
    }

    function updateActionButtons() {
        const view = state.gameView;
        const canDraw = isMyPhase("MUST_DRAW");
        const canDiscardPhase = isMyPhase("MUST_DISCARD");
        const selectedId = board.getSelectedCardId();
        const isForbiddenDiscard = Boolean(
            selectedId && view && view.forbiddenDiscardCardId === selectedId
        );

        el.btnDrawDeck.disabled = !canDraw;
        el.btnDrawDiscard.disabled = !(canDraw && view && view.topDiscardCard);
        el.btnDiscard.disabled = !(canDiscardPhase && selectedId && !isForbiddenDiscard);
        el.btnDeclareClose.disabled = !canDraw;
    }

    function copyTextSafe(text) {
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).catch(() => {});
            return;
        }
        const temp = document.createElement("textarea");
        temp.value = text;
        temp.style.position = "fixed";
        temp.style.opacity = "0";
        document.body.appendChild(temp);
        temp.select();
        try { document.execCommand("copy"); } catch { /* ignore */ }
        temp.remove();
    }

    function renderRoundSummaryModal(summary) {
        state.lastSummaryRound = Math.max(state.lastSummaryRound, summary.roundNumber);
        const playersById = new Map((state.gameView?.players || []).map((p) => [p.playerId, p]));
        const winnerName = playersById.get(summary.winnerPlayerId)?.name || summary.winnerPlayerId;

        if (summary.closeType === "VICTORIA_POR_ABANDONO") {
            el.summaryTitle.textContent = "🏆 ¡Victoria por Abandono!";
            el.summarySubtitle.textContent = `${winnerName} gana la partida porque los demás jugadores abandonaron.`;
        } else {
            el.summaryTitle.textContent = summary.matchFinished
                ? `🏆 ¡Fin de la Partida! (Ronda ${summary.roundNumber})`
                : `Cierre de Ronda ${summary.roundNumber}`;

            const bonusText = summary.closeBonus > 0 ? `+${summary.closeBonus}` : String(summary.closeBonus);
            el.summarySubtitle.textContent =
                `${winnerName} cerró con ${summary.closeType} (${bonusText} pts).`;
        }

        el.summaryTableBody.innerHTML = "";
        (summary.scores || []).forEach((s) => {
            const tr = document.createElement("tr");
            const playerObj = playersById.get(s.playerId);
            const name = playerObj?.name || s.playerId;
            const deltaText = s.roundDelta > 0 ? `+${s.roundDelta}` : String(s.roundDelta);
            const statusText = playerObj?.status === "ABANDONADO"
                ? "ABANDONÓ"
                : (s.eliminated ? "ELIMINADO" : "ACTIVO");
            const isOut = s.eliminated || playerObj?.status === "ABANDONADO";
            tr.innerHTML = `
                <td>${escapeHtml(name)}</td>
                <td>${deltaText}</td>
                <td><strong>${s.totalScore}</strong></td>
                <td><span class="status-pill ${isOut ? "status-eliminated" : "status-active"}">${statusText}</span></td>
            `;
            el.summaryTableBody.appendChild(tr);
        });

        el.summaryModal.classList.remove("hidden");
    }

    function escapeHtml(text) {
        return String(text ?? "")
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    init();
})();

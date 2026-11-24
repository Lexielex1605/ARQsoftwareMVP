package app.deck.service;

import app.core.Card;
import app.core.CardBack;
import app.core.GameConfig;
import app.core.Rank;
import app.core.Suit;

import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;

/**
 * Implementación del administrador de mazo y pozo de descarte.
 * Soporta de 1 a 4 barajas, reverso rojo (barajas impares) y azul (barajas pares),
 * Jokers opcionales (2 por baraja) y reciclado automático del descarte conservando
 * la última carta visible.
 */
public final class DeckManager implements IDeckService {

    private final Random random;
    private final List<Card> drawPile = new ArrayList<>();
    private final Deque<Card> discardPile = new ArrayDeque<>();

    public DeckManager() {
        this(new SecureRandom());
    }

    public DeckManager(Random random) {
        this.random = Objects.requireNonNull(random, "El generador aleatorio no puede ser nulo");
    }

    @Override
    public void initialize(GameConfig config) {
        Objects.requireNonNull(config, "La configuración de juego no puede ser nula");
        config.validate();
        initialize(config.getDeckCount(), config.isIncludeJokers());
    }

    @Override
    public void initialize(int deckCount, boolean includeJokers) {
        if (deckCount < GameConfig.MIN_DECKS || deckCount > GameConfig.MAX_DECKS) {
            throw new IllegalArgumentException(
                    "La cantidad de barajas debe estar entre " + GameConfig.MIN_DECKS
                            + " y " + GameConfig.MAX_DECKS + ", recibido: " + deckCount);
        }
        drawPile.clear();
        discardPile.clear();

        for (int deckNumber = 1; deckNumber <= deckCount; deckNumber++) {
            addSingleDeck(deckNumber, includeJokers);
        }
        shuffle();
    }

    @Override
    public void shuffle() {
        Collections.shuffle(drawPile, random);
    }

    @Override
    public Card drawFromDeck() {
        if (drawPile.isEmpty()) {
            recycleDiscardPile();
        }
        if (drawPile.isEmpty()) {
            throw new IllegalStateException(
                    "No hay cartas disponibles en el mazo ni cartas reciclables en el pozo de descarte");
        }
        return drawPile.remove(drawPile.size() - 1);
    }

    @Override
    public Card drawFromDiscard() {
        if (discardPile.isEmpty()) {
            throw new IllegalStateException("El pozo de descarte está vacío");
        }
        return discardPile.pop();
    }

    @Override
    public void discard(Card card) {
        Objects.requireNonNull(card, "No se puede descartar una carta nula");
        discardPile.push(card);
    }

    @Override
    public Optional<Card> peekDiscard() {
        return Optional.ofNullable(discardPile.peek());
    }

    @Override
    public Optional<CardBack> peekDeckBack() {
        if (drawPile.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(drawPile.get(drawPile.size() - 1).back());
    }

    @Override
    public List<Card> dealHand(int cardCount) {
        if (cardCount <= 0) {
            throw new IllegalArgumentException("La cantidad de cartas a repartir debe ser > 0, recibido: " + cardCount);
        }
        List<Card> hand = new ArrayList<>(cardCount);
        for (int i = 0; i < cardCount; i++) {
            hand.add(drawFromDeck());
        }
        return hand;
    }

    @Override
    public boolean recycleDiscardPile() {
        if (discardPile.size() <= 1) {
            return false;
        }
        Card topVisibleCard = discardPile.pop();
        drawPile.addAll(discardPile);
        discardPile.clear();
        discardPile.push(topVisibleCard);
        shuffle();
        return true;
    }

    @Override
    public int remainingDeckSize() {
        return drawPile.size();
    }

    @Override
    public int discardPileSize() {
        return discardPile.size();
    }

    private void addSingleDeck(int deckNumber, boolean includeJokers) {
        CardBack back = CardBack.forDeckNumber(deckNumber);
        for (Suit suit : Suit.values()) {
            if (!suit.isStandard()) {
                continue;
            }
            for (Rank rank : Rank.values()) {
                if (!rank.isStandard()) {
                    continue;
                }
                String id = "D" + deckNumber + "-" + suit.name() + "-" + rank.name();
                drawPile.add(new Card(id, suit, rank, back, deckNumber));
            }
        }
        if (includeJokers) {
            drawPile.add(new Card("D" + deckNumber + "-JOKER-RED", Suit.JOKER, Rank.JOKER, back, deckNumber));
            drawPile.add(new Card("D" + deckNumber + "-JOKER-BLACK", Suit.JOKER, Rank.JOKER, back, deckNumber));
        }
    }
}

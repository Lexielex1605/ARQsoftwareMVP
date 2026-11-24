package app.rules.engine;

import app.core.Card;
import app.core.CyclePolicy;
import app.core.Rank;
import app.core.Suit;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Valida si un conjunto de cartas forma una escalera, terna/cuarta o espejo válido.
 * Todos los métodos se mantienen con complejidad ciclomática baja (RG-04).
 */
public final class MeldValidator {

    private static final int MIN_MELD_SIZE = 3;
    private static final int MAX_SET_SIZE = 4;
    private static final int MAX_STRAIGHT_SIZE = 13;

    public Optional<MeldType> classify(List<Card> cards, CyclePolicy cyclePolicy) {
        Objects.requireNonNull(cards, "Las cartas no pueden ser nulas");
        Objects.requireNonNull(cyclePolicy, "La política de ciclos no puede ser nula");
        if (cards.size() < MIN_MELD_SIZE) {
            return Optional.empty();
        }
        if (isValidStraight(cards, cyclePolicy)) {
            return Optional.of(MeldType.STRAIGHT);
        }
        if (isValidSet(cards)) {
            return Optional.of(MeldType.SET);
        }
        if (isValidMirror(cards)) {
            return Optional.of(MeldType.MIRROR);
        }
        return Optional.empty();
    }

    public boolean isValidMeld(List<Card> cards, CyclePolicy cyclePolicy) {
        return classify(cards, cyclePolicy).isPresent();
    }

    public boolean isValidStraight(List<Card> cards, CyclePolicy cyclePolicy) {
        if (cards.size() < MIN_MELD_SIZE || cards.size() > MAX_STRAIGHT_SIZE) {
            return false;
        }
        List<Card> nonJokers = extractNonJokers(cards);
        int jokerCount = cards.size() - nonJokers.size();
        if (!allSameSuit(nonJokers)) {
            return false;
        }
        return cyclePolicy.isValidSequence(extractRanks(nonJokers), jokerCount);
    }

    public boolean isValidSet(List<Card> cards) {
        if (cards.size() < MIN_MELD_SIZE || cards.size() > MAX_SET_SIZE) {
            return false;
        }
        List<Card> nonJokers = extractNonJokers(cards);
        return allSameRank(nonJokers) && allDistinctSuits(nonJokers);
    }

    public boolean isValidMirror(List<Card> cards) {
        if (cards.size() < MIN_MELD_SIZE || cards.size() > MAX_SET_SIZE) {
            return false;
        }
        List<Card> nonJokers = extractNonJokers(cards);
        if (nonJokers.size() < 2) {
            return false;
        }
        return allSameRank(nonJokers) && !allDistinctSuits(nonJokers);
    }

    private List<Card> extractNonJokers(List<Card> cards) {
        List<Card> result = new ArrayList<>(cards.size());
        for (Card card : cards) {
            if (!card.isJoker()) {
                result.add(card);
            }
        }
        return result;
    }

    private List<Rank> extractRanks(List<Card> cards) {
        List<Rank> ranks = new ArrayList<>(cards.size());
        for (Card card : cards) {
            ranks.add(card.rank());
        }
        return ranks;
    }

    private boolean allSameSuit(List<Card> cards) {
        if (cards.isEmpty()) {
            return true;
        }
        Suit expected = cards.get(0).suit();
        for (Card card : cards) {
            if (card.suit() != expected) {
                return false;
            }
        }
        return true;
    }

    private boolean allSameRank(List<Card> cards) {
        if (cards.isEmpty()) {
            return true;
        }
        Rank expected = cards.get(0).rank();
        for (Card card : cards) {
            if (card.rank() != expected) {
                return false;
            }
        }
        return true;
    }

    private boolean allDistinctSuits(List<Card> cards) {
        Set<Suit> seen = EnumSet.noneOf(Suit.class);
        for (Card card : cards) {
            if (!seen.add(card.suit())) {
                return false;
            }
        }
        return true;
    }
}

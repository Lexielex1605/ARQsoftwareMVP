package app.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Política de evaluación de secuencias (escaleras) lineales o cíclicas.
 * Cada constante define polimórficamente cómo se valida una escalera con o sin Jokers.
 */
public enum CyclePolicy {
    /**
     * Secuencia estrictamente lineal con As bajo (A-2-3-...-K). No permite Q-K-A ni K-A-2.
     */
    LINEAR_ACE_LOW("Lineal estricta (A-2-...-K)") {
        @Override
        protected boolean fitsSequence(List<Rank> distinctNonJokers, int totalLength) {
            return fitsLinear(distinctNonJokers, totalLength, false);
        }
    },

    /**
     * Secuencia lineal donde el As puede ser bajo (A-2-3) o alto (Q-K-A), sin vuelta (K-A-2 inválido).
     */
    LINEAR("Lineal con As bajo o alto (A-2-3 y Q-K-A, sin vuelta)") {
        @Override
        protected boolean fitsSequence(List<Rank> distinctNonJokers, int totalLength) {
            return fitsLinear(distinctNonJokers, totalLength, false)
                    || fitsLinear(distinctNonJokers, totalLength, true);
        }
    },

    /**
     * Secuencia cíclica (permite vuelta completa a través del As, p. ej. Q-K-A, K-A-2, Q-K-A-2).
     */
    CYCLIC("Cíclica (permite vuelta K-A-2)") {
        @Override
        protected boolean fitsSequence(List<Rank> distinctNonJokers, int totalLength) {
            if (distinctNonJokers.size() <= 1) {
                return true;
            }
            List<Integer> positions = new ArrayList<>(distinctNonJokers.size());
            for (Rank rank : distinctNonJokers) {
                positions.add(rank.getOrder() - 1);
            }
            Collections.sort(positions);

            int maxGap = computeMaxCircularGap(positions);
            int minArcLength = RANKS_PER_SUIT - maxGap;
            return minArcLength <= totalLength;
        }
    };

    private static final int MIN_SEQUENCE_LENGTH = 3;
    private static final int RANKS_PER_SUIT = 13;

    private final String description;

    CyclePolicy(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Evalúa si una lista de rangos (que puede incluir {@link Rank#JOKER})
     * puede formar una escalera válida bajo esta política.
     */
    public boolean isValidSequence(List<Rank> ranks) {
        Objects.requireNonNull(ranks, "La lista de rangos no puede ser nula");
        List<Rank> nonJokers = new ArrayList<>();
        int jokerCount = 0;
        for (Rank rank : ranks) {
            Objects.requireNonNull(rank, "Ningún rango en la secuencia puede ser nulo");
            if (rank.isJoker()) {
                jokerCount++;
            } else {
                nonJokers.add(rank);
            }
        }
        return isValidSequence(nonJokers, jokerCount);
    }

    /**
     * Evalúa si los rangos estándar dados junto con {@code jokerCount} comodines
     * forman una escalera válida bajo esta política.
     */
    public boolean isValidSequence(List<Rank> nonJokerRanks, int jokerCount) {
        Objects.requireNonNull(nonJokerRanks, "La lista de rangos no puede ser nula");
        if (jokerCount < 0) {
            throw new IllegalArgumentException("La cantidad de Jokers no puede ser negativa: " + jokerCount);
        }
        int totalLength = nonJokerRanks.size() + jokerCount;
        if (totalLength < MIN_SEQUENCE_LENGTH || totalLength > RANKS_PER_SUIT) {
            return false;
        }
        if (!allStandardAndDistinct(nonJokerRanks)) {
            return false;
        }
        return fitsSequence(nonJokerRanks, totalLength);
    }

    protected abstract boolean fitsSequence(List<Rank> distinctNonJokers, int totalLength);

    private static boolean allStandardAndDistinct(List<Rank> ranks) {
        Set<Rank> seen = EnumSet.noneOf(Rank.class);
        for (Rank rank : ranks) {
            Objects.requireNonNull(rank, "Ningún rango puede ser nulo");
            if (rank.isJoker() || !seen.add(rank)) {
                return false;
            }
        }
        return true;
    }

    private static boolean fitsLinear(List<Rank> distinctNonJokers, int totalLength, boolean aceHigh) {
        if (distinctNonJokers.isEmpty()) {
            return true;
        }
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Rank rank : distinctNonJokers) {
            int pos = (aceHigh && rank == Rank.ACE) ? 14 : rank.getOrder();
            if (pos < min) {
                min = pos;
            }
            if (pos > max) {
                max = pos;
            }
        }
        return (max - min + 1) <= totalLength;
    }

    private static int computeMaxCircularGap(List<Integer> sortedPositions) {
        int size = sortedPositions.size();
        int maxGap = 0;
        for (int i = 0; i < size - 1; i++) {
            int gap = sortedPositions.get(i + 1) - sortedPositions.get(i) - 1;
            if (gap > maxGap) {
                maxGap = gap;
            }
        }
        int wrapGap = (sortedPositions.get(0) + RANKS_PER_SUIT) - sortedPositions.get(size - 1) - 1;
        return Math.max(maxGap, wrapGap);
    }
}

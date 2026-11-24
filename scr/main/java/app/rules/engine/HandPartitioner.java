package app.rules.engine;

import app.core.Card;
import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.GameConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Algoritmo exacto de partición óptima para manos de hasta 10 cartas (o 12 con robo).
 * Detecta combinaciones válidas (escaleras, ternas/cuartas, espejos y Jokers) mediante
 * búsqueda con máscaras de bits y programación dinámica manteniendo CC <= 10 (RG-04).
 */
public final class HandPartitioner {

    private static final int MAX_SUPPORTED_HAND_SIZE = 12;

    private final MeldValidator meldValidator;

    public HandPartitioner() {
        this(new MeldValidator());
    }

    public HandPartitioner(MeldValidator meldValidator) {
        this.meldValidator = Objects.requireNonNull(meldValidator, "El validador no puede ser nulo");
    }

    /**
     * Encuentra la partición óptima que minimiza el puntaje de cartas sobrantes.
     */
    public HandPartition findBestPartition(List<Card> hand, GameConfig config) {
        Objects.requireNonNull(hand, "La mano no puede ser nula");
        Objects.requireNonNull(config, "La configuración no puede ser nula");
        validateHandSize(hand);

        int size = hand.size();
        if (size == 0) {
            return new HandPartition(List.of(), List.of(), 0);
        }

        List<IndexedMeld> validMelds = enumerateValidMelds(hand, config.getCyclePolicy(), Set.of());
        int[] cardValues = computeCardValues(hand, config.getCardValuePolicy());
        HandPartition[] memo = new HandPartition[1 << size];
        int fullMask = (1 << size) - 1;
        return solveBest(fullMask, hand, validMelds, cardValues, memo);
    }

    /**
     * Busca una partición exacta que cubra toda la mano usando combinaciones con los tamaños exactos indicados.
     * Por ejemplo: [10], [7, 3], [6, 4], [4, 3, 3] o [3, 3, 3].
     */
    public Optional<HandPartition> findExactPartition(
            List<Card> hand,
            List<Integer> targetSizes,
            CyclePolicy cyclePolicy
    ) {
        Objects.requireNonNull(hand, "La mano no puede ser nula");
        Objects.requireNonNull(targetSizes, "Los tamaños objetivo no pueden ser nulos");
        Objects.requireNonNull(cyclePolicy, "La política de ciclos no puede ser nula");
        validateHandSize(hand);

        if (sumSizes(targetSizes) != hand.size()) {
            return Optional.empty();
        }

        Set<Integer> allowedSizes = Set.copyOf(targetSizes);
        List<IndexedMeld> validMelds = enumerateValidMelds(hand, cyclePolicy, allowedSizes);
        Map<Integer, List<IndexedMeld>> bySize = groupBySize(validMelds);

        List<Meld> chosen = new ArrayList<>(targetSizes.size());
        int fullMask = (1 << hand.size()) - 1;
        if (!matchTargetSizes(0, fullMask, targetSizes, bySize, chosen)) {
            return Optional.empty();
        }
        return Optional.of(new HandPartition(chosen, List.of(), 0));
    }

    private HandPartition solveBest(
            int mask,
            List<Card> hand,
            List<IndexedMeld> validMelds,
            int[] cardValues,
            HandPartition[] memo
    ) {
        if (memo[mask] != null) {
            return memo[mask];
        }
        HandPartition best = buildDeadwoodOnly(mask, hand, cardValues);
        for (IndexedMeld candidate : validMelds) {
            if ((candidate.mask() & mask) == candidate.mask()) {
                HandPartition rest = solveBest(mask ^ candidate.mask(), hand, validMelds, cardValues, memo);
                HandPartition combined = prependMeld(candidate.meld(), rest);
                if (isBetter(combined, best)) {
                    best = combined;
                }
            }
        }
        memo[mask] = best;
        return best;
    }

    private boolean matchTargetSizes(
            int index,
            int remainingMask,
            List<Integer> targetSizes,
            Map<Integer, List<IndexedMeld>> bySize,
            List<Meld> chosen
    ) {
        if (index == targetSizes.size()) {
            return remainingMask == 0;
        }
        int requiredSize = targetSizes.get(index);
        List<IndexedMeld> candidates = bySize.getOrDefault(requiredSize, List.of());
        for (IndexedMeld candidate : candidates) {
            if ((candidate.mask() & remainingMask) == candidate.mask()) {
                chosen.add(candidate.meld());
                if (matchTargetSizes(index + 1, remainingMask ^ candidate.mask(), targetSizes, bySize, chosen)) {
                    return true;
                }
                chosen.remove(chosen.size() - 1);
            }
        }
        return false;
    }

    private List<IndexedMeld> enumerateValidMelds(
            List<Card> hand,
            CyclePolicy cyclePolicy,
            Set<Integer> allowedSizes
    ) {
        int totalMasks = 1 << hand.size();
        List<IndexedMeld> result = new ArrayList<>();
        for (int mask = 1; mask < totalMasks; mask++) {
            int bits = Integer.bitCount(mask);
            if (!isEligibleSize(bits, allowedSizes)) {
                continue;
            }
            List<Card> subset = extractSubset(hand, mask);
            Optional<MeldType> type = meldValidator.classify(subset, cyclePolicy);
            if (type.isPresent()) {
                result.add(new IndexedMeld(mask, new Meld(type.get(), subset)));
            }
        }
        return result;
    }

    private boolean isEligibleSize(int bits, Set<Integer> allowedSizes) {
        if (bits < 3) {
            return false;
        }
        return allowedSizes.isEmpty() || allowedSizes.contains(bits);
    }

    private Map<Integer, List<IndexedMeld>> groupBySize(List<IndexedMeld> melds) {
        Map<Integer, List<IndexedMeld>> bySize = new HashMap<>();
        for (IndexedMeld item : melds) {
            bySize.computeIfAbsent(item.meld().size(), key -> new ArrayList<>()).add(item);
        }
        return bySize;
    }

    private List<Card> extractSubset(List<Card> hand, int mask) {
        List<Card> subset = new ArrayList<>(Integer.bitCount(mask));
        for (int i = 0; i < hand.size(); i++) {
            if ((mask & (1 << i)) != 0) {
                subset.add(hand.get(i));
            }
        }
        return subset;
    }

    private int[] computeCardValues(List<Card> hand, CardValuePolicy policy) {
        int[] values = new int[hand.size()];
        for (int i = 0; i < hand.size(); i++) {
            values[i] = policy.valueOf(hand.get(i));
        }
        return values;
    }

    private HandPartition buildDeadwoodOnly(int mask, List<Card> hand, int[] cardValues) {
        List<Card> deadwood = new ArrayList<>(Integer.bitCount(mask));
        int points = 0;
        for (int i = 0; i < hand.size(); i++) {
            if ((mask & (1 << i)) != 0) {
                deadwood.add(hand.get(i));
                points += cardValues[i];
            }
        }
        return new HandPartition(List.of(), deadwood, points);
    }

    private HandPartition prependMeld(Meld meld, HandPartition rest) {
        List<Meld> melds = new ArrayList<>(rest.melds().size() + 1);
        melds.add(meld);
        melds.addAll(rest.melds());
        return new HandPartition(melds, rest.deadwood(), rest.deadwoodPoints());
    }

    private boolean isBetter(HandPartition candidate, HandPartition current) {
        if (candidate.deadwoodPoints() != current.deadwoodPoints()) {
            return candidate.deadwoodPoints() < current.deadwoodPoints();
        }
        if (candidate.meldedCardCount() != current.meldedCardCount()) {
            return candidate.meldedCardCount() > current.meldedCardCount();
        }
        return candidate.melds().size() < current.melds().size();
    }

    private int sumSizes(List<Integer> targetSizes) {
        int total = 0;
        for (int size : targetSizes) {
            total += size;
        }
        return total;
    }

    private void validateHandSize(List<Card> hand) {
        if (hand.size() > MAX_SUPPORTED_HAND_SIZE) {
            throw new IllegalArgumentException(
                    "Tamaño máximo de mano soportado: " + MAX_SUPPORTED_HAND_SIZE + ", recibido: " + hand.size());
        }
    }

    private record IndexedMeld(int mask, Meld meld) {
    }
}

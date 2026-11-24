package app.rules.apuntado;

import app.core.Card;
import app.core.GameConfig;
import app.core.GameMode;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.HandPartition;
import app.rules.engine.HandPartitioner;
import app.rules.engine.IGameRulesStrategy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Estrategia de reglas para la modalidad Apuntado a 101:
 * únicamente permite cierre 3-3-3 (3 ternas de 3 cartas) + 1 carta sobrante estrictamente menor
 * al límite configurado en {@link GameConfig#getApuntadoCloseLimit()} (por defecto < 5 pts),
 * sumando el valor de esa sobrante al puntaje de quien declara el cierre.
 * Totalmente independiente de app.rules.classic (RG-02) y con CC <= 10 (RG-04).
 */
public final class ApuntadoRulesStrategy implements IGameRulesStrategy {

    public static final String CLOSE_TYPE = "APUNTADO";
    public static final int MAX_LEFTOVER_EXCLUSIVE = GameConfig.DEFAULT_APUNTADO_CLOSE_LIMIT;

    private static final List<Integer> THREE_TERNAS_PATTERN = List.of(3, 3, 3);

    private final HandPartitioner partitioner;

    public ApuntadoRulesStrategy() {
        this(new HandPartitioner());
    }

    public ApuntadoRulesStrategy(HandPartitioner partitioner) {
        this.partitioner = Objects.requireNonNull(partitioner, "El particionador no puede ser nulo");
    }

    public String name() {
        return "apuntado";
    }

    @Override
    public GameMode getMode() {
        return GameMode.APUNTADO;
    }

    @Override
    public CloseEvaluation evaluateClose(List<Card> hand, GameConfig config) {
        Objects.requireNonNull(hand, "La mano no puede ser nula");
        Objects.requireNonNull(config, "La configuración no puede ser nula");

        if (hand.size() != GameConfig.HAND_SIZE) {
            return CloseEvaluation.invalid(partitioner.findBestPartition(hand, config));
        }

        Optional<HandPartition> apuntadoClose = findThreeMeldsWithLowDeadwood(hand, config);
        if (apuntadoClose.isPresent()) {
            HandPartition partition = apuntadoClose.get();
            return CloseEvaluation.valid(CLOSE_TYPE, partition.deadwoodPoints(), partition);
        }

        return CloseEvaluation.invalid(partitioner.findBestPartition(hand, config));
    }

    @Override
    public HandPartition evaluateHand(List<Card> hand, GameConfig config) {
        return partitioner.findBestPartition(hand, config);
    }

    private Optional<HandPartition> findThreeMeldsWithLowDeadwood(List<Card> hand, GameConfig config) {
        int maxExclusive = config.getApuntadoCloseLimit();
        HandPartition best = null;
        for (int i = 0; i < hand.size(); i++) {
            Card candidateLeftover = hand.get(i);
            int points = config.getCardValuePolicy().valueOf(candidateLeftover);
            if (!isBetterCandidate(points, maxExclusive, best)) {
                continue;
            }
            List<Card> nineCards = removeCardAt(hand, i);
            Optional<HandPartition> match = partitioner.findExactPartition(
                    nineCards, THREE_TERNAS_PATTERN, config.getCyclePolicy());
            if (match.isPresent()) {
                best = new HandPartition(match.get().melds(), List.of(candidateLeftover), points);
            }
        }
        return Optional.ofNullable(best);
    }

    private boolean isBetterCandidate(int points, int maxExclusive, HandPartition currentBest) {
        if (points >= maxExclusive) {
            return false;
        }
        return currentBest == null || points < currentBest.deadwoodPoints();
    }

    private List<Card> removeCardAt(List<Card> hand, int excludedIndex) {
        List<Card> result = new ArrayList<>(hand.size() - 1);
        for (int i = 0; i < hand.size(); i++) {
            if (i != excludedIndex) {
                result.add(hand.get(i));
            }
        }
        return result;
    }
}

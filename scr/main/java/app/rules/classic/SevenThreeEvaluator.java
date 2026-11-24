package app.rules.classic;

import app.core.Card;
import app.core.GameConfig;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.HandPartitioner;

import java.util.List;
import java.util.Optional;

/**
 * Evaluador de cierre Clásica: escalera de 7 cartas + terna de 3 cartas (-20 pts).
 */
public final class SevenThreeEvaluator implements IClassicCloseEvaluator {

    public static final String CLOSE_TYPE = "SEVEN_THREE";
    public static final int BONUS_POINTS = -20;
    private static final List<Integer> PATTERN = List.of(7, 3);

    @Override
    public String getCloseType() {
        return CLOSE_TYPE;
    }

    @Override
    public int getBonusPoints() {
        return BONUS_POINTS;
    }

    @Override
    public Optional<CloseEvaluation> evaluate(List<Card> hand, GameConfig config, HandPartitioner partitioner) {
        if (hand.size() != GameConfig.HAND_SIZE) {
            return Optional.empty();
        }
        return partitioner.findExactPartition(hand, PATTERN, config.getCyclePolicy())
                .map(partition -> CloseEvaluation.valid(CLOSE_TYPE, BONUS_POINTS, partition));
    }
}

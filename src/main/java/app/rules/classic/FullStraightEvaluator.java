package app.rules.classic;

import app.core.Card;
import app.core.GameConfig;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.HandPartitioner;

import java.util.List;
import java.util.Optional;

/**
 * Evaluador de cierre Clásica: escalera completa de 10 cartas (-25 pts).
 */
public final class FullStraightEvaluator implements IClassicCloseEvaluator {

    public static final String CLOSE_TYPE = "FULL_STRAIGHT";
    public static final int BONUS_POINTS = -25;
    private static final List<Integer> PATTERN = List.of(10);

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

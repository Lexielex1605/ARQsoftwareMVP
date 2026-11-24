package app.rules.classic;

import app.core.Card;
import app.core.GameConfig;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.HandPartitioner;

import java.util.List;
import java.util.Optional;

/**
 * Evaluador de cierre Clásica: cuarta de 4 cartas + dos ternas de 3 cartas (-10 pts).
 */
public final class FourThreeThreeEvaluator implements IClassicCloseEvaluator {

    public static final String CLOSE_TYPE = "FOUR_THREE_THREE";
    public static final int BONUS_POINTS = -10;
    private static final List<Integer> PATTERN = List.of(4, 3, 3);

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

package app.rules.classic;

import app.core.Card;
import app.core.GameConfig;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.HandPartition;
import app.rules.engine.HandPartitioner;
import app.rules.engine.Meld;
import app.rules.engine.MeldType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Evaluador de cierre especial: 4 Jokers en mano (-25 pts).
 */
public final class FourJokersEvaluator implements IClassicCloseEvaluator {

    public static final String CLOSE_TYPE = "FOUR_JOKERS";
    public static final int BONUS_POINTS = -25;
    private static final int REQUIRED_JOKERS = 4;

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
        List<Card> jokers = extractJokers(hand);
        if (jokers.size() < REQUIRED_JOKERS) {
            return Optional.empty();
        }
        Meld jokerMeld = new Meld(MeldType.SET, jokers.subList(0, REQUIRED_JOKERS));
        HandPartition partition = new HandPartition(List.of(jokerMeld), List.of(), 0);
        return Optional.of(CloseEvaluation.valid(CLOSE_TYPE, BONUS_POINTS, partition));
    }

    private List<Card> extractJokers(List<Card> hand) {
        List<Card> jokers = new ArrayList<>();
        for (Card card : hand) {
            if (card.isJoker()) {
                jokers.add(card);
            }
        }
        return jokers;
    }
}

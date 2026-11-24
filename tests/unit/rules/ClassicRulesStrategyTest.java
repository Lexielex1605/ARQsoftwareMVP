package unit.rules;

import app.core.Card;
import app.core.CardBack;
import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.core.GameMode;
import app.core.Rank;
import app.core.Suit;
import app.rules.classic.ClassicRulesStrategy;
import app.rules.classic.FourJokersEvaluator;
import app.rules.classic.FourSixEvaluator;
import app.rules.classic.FourThreeThreeEvaluator;
import app.rules.classic.FullStraightEvaluator;
import app.rules.classic.SevenThreeEvaluator;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.IRuleEngine;
import app.rules.engine.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClassicRulesStrategyTest {

    private final IRuleEngine ruleEngine = new RuleEngine(List.of(new ClassicRulesStrategy()));

    private static Card card(Suit suit, Rank rank, int deck) {
        return new Card("D" + deck + "-" + suit + "-" + rank, suit, rank, CardBack.forDeckNumber(deck), deck);
    }

    private static Card joker(int deck, String color) {
        return new Card("D" + deck + "-JOKER-" + color, Suit.JOKER, Rank.JOKER, CardBack.forDeckNumber(deck), deck);
    }

    private static GameConfig classicConfig(CyclePolicy cyclePolicy) {
        return new GameConfig(
                GameMode.CLASSIC, 4, 2, true,
                CardValuePolicy.STANDARD, cyclePolicy, EliminationLimit.LIMIT_101
        );
    }

    @Test
    void detectsFourJokersCloseWithMinus25Points() {
        List<Card> hand = List.of(
                joker(1, "RED"),
                joker(1, "BLACK"),
                joker(2, "RED"),
                joker(2, "BLACK"),
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.HEARTS, Rank.FIVE, 1),
                card(Suit.CLUBS, Rank.EIGHT, 1),
                card(Suit.DIAMONDS, Rank.JACK, 1),
                card(Suit.SPADES, Rank.KING, 1),
                card(Suit.HEARTS, Rank.NINE, 1)
        );

        CloseEvaluation eval = ruleEngine.evaluateClose(hand, classicConfig(CyclePolicy.LINEAR));
        assertTrue(eval.canClose());
        assertEquals(FourJokersEvaluator.CLOSE_TYPE, eval.closeType());
        assertEquals(-25, eval.closeBonus());
    }

    @Test
    void detectsFullTenCardStraightAndPrioritizesMinus25OverSmallerPatterns() {
        // Escalera cíclica de 10 cartas: J-Q-K-A-2-3-4-5-6-7 de Picas (1 Joker en lugar de 4♠)
        List<Card> hand = List.of(
                card(Suit.SPADES, Rank.JACK, 1),
                card(Suit.SPADES, Rank.QUEEN, 1),
                card(Suit.SPADES, Rank.KING, 1),
                card(Suit.SPADES, Rank.ACE, 1),
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                joker(1, "RED"),
                card(Suit.SPADES, Rank.FIVE, 1),
                card(Suit.SPADES, Rank.SIX, 1),
                card(Suit.SPADES, Rank.SEVEN, 1)
        );

        CloseEvaluation cyclicEval = ruleEngine.evaluateClose(hand, classicConfig(CyclePolicy.CYCLIC));
        assertTrue(cyclicEval.canClose());
        assertEquals(FullStraightEvaluator.CLOSE_TYPE, cyclicEval.closeType());
        assertEquals(-25, cyclicEval.closeBonus());

        // Bajo política lineal, K-A-2 no puede formar escalera continua de 10 cartas
        CloseEvaluation linearEval = ruleEngine.evaluateClose(hand, classicConfig(CyclePolicy.LINEAR));
        assertFalse(linearEval.closeType().equals(FullStraightEvaluator.CLOSE_TYPE));
    }

    @Test
    void detectsSevenThreeCloseWithMinus20Points() {
        List<Card> hand = List.of(
                // Escalera de 7 en Corazones
                card(Suit.HEARTS, Rank.THREE, 1),
                card(Suit.HEARTS, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.FIVE, 1),
                card(Suit.HEARTS, Rank.SIX, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.EIGHT, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                // Terna de Reyes
                card(Suit.SPADES, Rank.KING, 1),
                card(Suit.DIAMONDS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.KING, 1)
        );

        CloseEvaluation eval = ruleEngine.evaluateClose(hand, classicConfig(CyclePolicy.LINEAR));
        assertTrue(eval.canClose());
        assertEquals(SevenThreeEvaluator.CLOSE_TYPE, eval.closeType());
        assertEquals(-20, eval.closeBonus());
    }

    @Test
    void detectsFourSixCloseWithMinus15Points() {
        List<Card> hand = List.of(
                // Escalera de 6 en Tréboles
                card(Suit.CLUBS, Rank.FOUR, 1),
                card(Suit.CLUBS, Rank.FIVE, 1),
                card(Suit.CLUBS, Rank.SIX, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.EIGHT, 1),
                card(Suit.CLUBS, Rank.NINE, 1),
                // Cuarta de Ases
                card(Suit.SPADES, Rank.ACE, 1),
                card(Suit.HEARTS, Rank.ACE, 1),
                card(Suit.DIAMONDS, Rank.ACE, 1),
                card(Suit.CLUBS, Rank.ACE, 1)
        );

        CloseEvaluation eval = ruleEngine.evaluateClose(hand, classicConfig(CyclePolicy.LINEAR));
        assertTrue(eval.canClose());
        assertEquals(FourSixEvaluator.CLOSE_TYPE, eval.closeType());
        assertEquals(-15, eval.closeBonus());
    }

    @Test
    void detectsFourThreeThreeCloseWithMinus10PointsIncludingMirror() {
        List<Card> hand = List.of(
                // Cuarta escalera en Diamantes (Q-K-A-2 cíclica)
                card(Suit.DIAMONDS, Rank.QUEEN, 1),
                card(Suit.DIAMONDS, Rank.KING, 1),
                card(Suit.DIAMONDS, Rank.ACE, 1),
                card(Suit.DIAMONDS, Rank.TWO, 1),
                // Terna de 5s
                card(Suit.SPADES, Rank.FIVE, 1),
                card(Suit.HEARTS, Rank.FIVE, 1),
                card(Suit.CLUBS, Rank.FIVE, 1),
                // Espejo de 9♥ (2 barajas + Joker)
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.HEARTS, Rank.NINE, 2),
                joker(1, "BLACK")
        );

        CloseEvaluation eval = ruleEngine.evaluateClose(hand, classicConfig(CyclePolicy.CYCLIC));
        assertTrue(eval.canClose());
        assertEquals(FourThreeThreeEvaluator.CLOSE_TYPE, eval.closeType());
        assertEquals(-10, eval.closeBonus());
    }

    @Test
    void rejectsIncompleteHandWithSingleLowDeadwoodCardInClassicMode() {
        // 3 ternas + 1 sobrante (válido en Apuntado, pero INVÁLIDO en Clásica)
        List<Card> hand = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.ACE, 1)
        );

        CloseEvaluation eval = ruleEngine.evaluateClose(hand, classicConfig(CyclePolicy.LINEAR));
        assertFalse(eval.canClose());
        assertEquals(0, eval.closeBonus());
        assertEquals(1, eval.partition().deadwoodPoints());
    }

    @Test
    void acceptsFourThreeThreeCloseWithMirrorsInMultiDeckPlay() {
        // Caso real de prueba de campo:
        // Cuarta de 6s con espejo (6♠, 6♠, 6♥, 6♣) + Terna de 8s (8♥, 8♦, 8♣) + Terna de Js con espejo (J♦, J♣, J♣)
        List<Card> hand = List.of(
                card(Suit.SPADES, Rank.SIX, 1),
                card(Suit.SPADES, Rank.SIX, 2),
                card(Suit.HEARTS, Rank.SIX, 1),
                card(Suit.CLUBS, Rank.SIX, 1),
                card(Suit.HEARTS, Rank.EIGHT, 1),
                card(Suit.DIAMONDS, Rank.EIGHT, 1),
                card(Suit.CLUBS, Rank.EIGHT, 1),
                card(Suit.DIAMONDS, Rank.JACK, 1),
                card(Suit.CLUBS, Rank.JACK, 1),
                card(Suit.CLUBS, Rank.JACK, 2)
        );

        GameConfig config = new GameConfig(
                GameMode.CLASSIC, 4, 2, true,
                CardValuePolicy.FACE_SEQUENTIAL, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101
        );

        CloseEvaluation eval = ruleEngine.evaluateClose(hand, config);
        assertTrue(eval.canClose());
        assertEquals(FourThreeThreeEvaluator.CLOSE_TYPE, eval.closeType());
        assertEquals(-10, eval.closeBonus());
        assertEquals(0, eval.partition().deadwoodPoints());
    }
}

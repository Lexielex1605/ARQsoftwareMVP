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
import app.rules.apuntado.ApuntadoRulesStrategy;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.IRuleEngine;
import app.rules.engine.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ApuntadoRulesStrategyTest {

    private final IRuleEngine ruleEngine = new RuleEngine(List.of(new ApuntadoRulesStrategy()));

    private static Card card(Suit suit, Rank rank, int deck) {
        return new Card("D" + deck + "-" + suit + "-" + rank, suit, rank, CardBack.forDeckNumber(deck), deck);
    }

    private static GameConfig apuntadoConfig(CardValuePolicy valuePolicy) {
        return new GameConfig(
                GameMode.APUNTADO, 4, 2, true,
                valuePolicy, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101
        );
    }

    @Test
    void allowsApuntadoCloseWithThreeMeldsAndDeadwoodBelow5() {
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
                card(Suit.CLUBS, Rank.FOUR, 1) // 4 pts < 5
        );

        CloseEvaluation eval = ruleEngine.evaluateClose(hand, apuntadoConfig(CardValuePolicy.STANDARD));
        assertTrue(eval.canClose());
        assertEquals(ApuntadoRulesStrategy.CLOSE_TYPE, eval.closeType());
        assertEquals(4, eval.closeBonus());
        assertEquals(3, eval.partition().melds().size());
        assertEquals(4, eval.partition().deadwoodPoints());
    }

    @Test
    void rejectsApuntadoCloseWhenDeadwoodIsFiveOrMore() {
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
                card(Suit.CLUBS, Rank.FIVE, 1) // 5 pts NO es < 5
        );

        CloseEvaluation eval = ruleEngine.evaluateClose(hand, apuntadoConfig(CardValuePolicy.STANDARD));
        assertFalse(eval.canClose());
        assertEquals(5, eval.partition().deadwoodPoints());
    }

    @Test
    void respectsCardValuePolicyWhenEvaluatingAceLeftover() {
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

        // Con STANDARD (As = 1): 1 < 5 -> cierra
        assertTrue(ruleEngine.evaluateClose(hand, apuntadoConfig(CardValuePolicy.STANDARD)).canClose());
        // Con ACE_TEN (As = 10): 10 >= 5 -> no cierra
        assertFalse(ruleEngine.evaluateClose(hand, apuntadoConfig(CardValuePolicy.ACE_TEN)).canClose());
    }

    @Test
    void enforcesStrictThreeThreeThreeWithLeftoverInApuntadoEvenForFourThreeThreeHands() {
        // Mano 4-3-3 con carta baja (2♠ en 2-3-4-5♠): en Apuntado cierra como 3-3-3 + sobrante 2♠ (+2 pts)
        List<Card> fourThreeThreeWithLowCard = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.SPADES, Rank.FIVE, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1)
        );

        CloseEvaluation evalLow = ruleEngine.evaluateClose(
                fourThreeThreeWithLowCard, apuntadoConfig(CardValuePolicy.STANDARD));
        assertTrue(evalLow.canClose());
        assertEquals(ApuntadoRulesStrategy.CLOSE_TYPE, evalLow.closeType());
        assertEquals(2, evalLow.closeBonus());
        assertEquals(2, evalLow.partition().deadwoodPoints());
        assertEquals(1, evalLow.partition().deadwood().size());

        // Mano 4-3-3 donde TODAS las cartas valen >= 5: NO cierra por Apuntado (< 5)
        List<Card> fourThreeThreeAllHigh = List.of(
                card(Suit.SPADES, Rank.SEVEN, 1),
                card(Suit.SPADES, Rank.EIGHT, 1),
                card(Suit.SPADES, Rank.NINE, 1),
                card(Suit.SPADES, Rank.TEN, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1)
        );

        CloseEvaluation evalHigh = ruleEngine.evaluateClose(
                fourThreeThreeAllHigh, apuntadoConfig(CardValuePolicy.STANDARD));
        assertFalse(evalHigh.canClose());
    }

    @Test
    void rejectsHandWithOnlyTwoMeldsEvenWhenDeadwoodIsBelowFive() {
        // Dos cuartas (8 cartas) + As (1) + Dos (2) = 3 pts sobrantes, pero solo 2 juegos (no 3 ternas)
        List<Card> hand = List.of(
                card(Suit.SPADES, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.SPADES, Rank.KING, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.DIAMONDS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.ACE, 1),
                card(Suit.HEARTS, Rank.TWO, 1)
        );

        CloseEvaluation eval = ruleEngine.evaluateClose(hand, apuntadoConfig(CardValuePolicy.STANDARD));
        assertFalse(eval.canClose());
    }

    @Test
    void respectsConfigurableApuntadoCloseLimitAndChargesExactLeftoverToCloser() {
        List<Card> handWithSevenLeftover = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.DIAMONDS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.NINE, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1) // 7 pts sobrante
        );

        // Con límite por defecto (< 5): rechazado
        assertFalse(ruleEngine.evaluateClose(handWithSevenLeftover, apuntadoConfig(CardValuePolicy.STANDARD)).canClose());

        // Con límite configurado < 8: aceptado y suma +7 pts al jugador que cerró
        GameConfig customLimit8 = new GameConfig(
                GameMode.APUNTADO, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101, 8
        );
        CloseEvaluation eval8 = ruleEngine.evaluateClose(handWithSevenLeftover, customLimit8);
        assertTrue(eval8.canClose());
        assertEquals(7, eval8.closeBonus());
        assertEquals(7, eval8.partition().deadwoodPoints());

        // Con límite configurado < 7: 7 NO es < 7 -> rechazado
        GameConfig customLimit7 = new GameConfig(
                GameMode.APUNTADO, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101, 7
        );
        assertFalse(ruleEngine.evaluateClose(handWithSevenLeftover, customLimit7).canClose());
    }
}

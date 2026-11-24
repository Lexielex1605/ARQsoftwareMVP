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
import app.rules.classic.ClassicRulesStrategy;
import app.rules.classic.SevenThreeEvaluator;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.HandPartition;
import app.rules.engine.IRuleEngine;
import app.rules.engine.RuleEngine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MixedModeRulesTest {

    private final IRuleEngine ruleEngine = new RuleEngine(List.of(
            new ClassicRulesStrategy(),
            new ApuntadoRulesStrategy()
    ));

    private static Card card(Suit suit, Rank rank, int deck) {
        return new Card("D" + deck + "-" + suit + "-" + rank, suit, rank, CardBack.forDeckNumber(deck), deck);
    }

    private static GameConfig mixedConfig() {
        return new GameConfig(
                GameMode.MIXED, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101
        );
    }

    @Test
    void validatesCloseAsRegularOrApuntadoAccordingToPlayerChoiceInMixedMode() {
        // Mano 7+3 válida como cierre Regular (-20 pts) y también como Apuntado limpio (0 pts)
        List<Card> sevenThreeHand = List.of(
                card(Suit.HEARTS, Rank.THREE, 1),
                card(Suit.HEARTS, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.FIVE, 1),
                card(Suit.HEARTS, Rank.SIX, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.EIGHT, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.SPADES, Rank.KING, 1),
                card(Suit.DIAMONDS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.KING, 1)
        );

        CloseEvaluation asClassic = ruleEngine.evaluateClose(sevenThreeHand, mixedConfig(), GameMode.CLASSIC);
        assertTrue(asClassic.canClose());
        assertEquals(SevenThreeEvaluator.CLOSE_TYPE, asClassic.closeType());
        assertEquals(-20, asClassic.closeBonus());

        CloseEvaluation asApuntado = ruleEngine.evaluateClose(sevenThreeHand, mixedConfig(), GameMode.APUNTADO);
        assertTrue(asApuntado.canClose());
        assertEquals(ApuntadoRulesStrategy.CLOSE_TYPE, asApuntado.closeType());
        assertEquals(3, asApuntado.closeBonus());
    }

    @Test
    void acceptsApuntadoOnlyHandWhenClosingAsApuntadoAndRejectsWhenClosingAsRegular() {
        // 3 ternas + 3♣ (3 pts < 5): válido en Apuntado, inválido en Regular
        List<Card> apuntadoOnlyHand = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.THREE, 1)
        );

        CloseEvaluation asApuntado = ruleEngine.evaluateClose(apuntadoOnlyHand, mixedConfig(), GameMode.APUNTADO);
        assertTrue(asApuntado.canClose());
        assertEquals(ApuntadoRulesStrategy.CLOSE_TYPE, asApuntado.closeType());
        assertEquals(3, asApuntado.closeBonus());

        CloseEvaluation asRegular = ruleEngine.evaluateClose(apuntadoOnlyHand, mixedConfig(), GameMode.CLASSIC);
        assertFalse(asRegular.canClose());
        assertEquals(3, asRegular.partition().deadwoodPoints());

        // En modo Mixto, omitir la elección de modalidad de cierre lanza error explícito
        assertThrows(IllegalArgumentException.class,
                () -> ruleEngine.evaluateClose(apuntadoOnlyHand, mixedConfig()));
        assertThrows(IllegalArgumentException.class,
                () -> ruleEngine.evaluateClose(apuntadoOnlyHand, mixedConfig(), GameMode.MIXED));

        // Evaluación de sobrantes en modo Mixto sigue calculando la partición óptima
        HandPartition partition = ruleEngine.evaluateHand(apuntadoOnlyHand, mixedConfig());
        assertEquals(3, partition.deadwoodPoints());
    }
}

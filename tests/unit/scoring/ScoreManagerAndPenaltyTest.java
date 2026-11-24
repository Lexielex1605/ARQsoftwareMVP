package unit.scoring;

import app.core.Card;
import app.core.CardBack;
import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.core.GameMode;
import app.core.Rank;
import app.core.Suit;
import app.scoring.calculator.PenaltyCalculator;
import app.scoring.service.IScoreService;
import app.scoring.service.RoundScoreResult;
import app.scoring.service.ScoreManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ScoreManagerAndPenaltyTest {

    private static Card card(Suit suit, Rank rank) {
        return new Card("D1-" + suit + "-" + rank, suit, rank, CardBack.RED, 1);
    }

    private static Card joker() {
        return new Card("D1-JOKER-RED", Suit.JOKER, Rank.JOKER, CardBack.RED, 1);
    }

    @Test
    void penaltyCalculatorSumsDeadwoodAccordingToPolicy() {
        PenaltyCalculator calculator = new PenaltyCalculator();
        List<Card> deadwood = List.of(
                card(Suit.SPADES, Rank.ACE),
                card(Suit.HEARTS, Rank.FIVE),
                card(Suit.CLUBS, Rank.QUEEN),
                card(Suit.DIAMONDS, Rank.KING),
                joker()
        );

        // STANDARD: 1 + 5 + 10 + 10 + 25 = 51
        assertEquals(51, calculator.calculateDeadwoodPenalty(deadwood, CardValuePolicy.STANDARD));
        // ACE_ELEVEN: 11 + 5 + 10 + 10 + 25 = 61
        assertEquals(61, calculator.calculateDeadwoodPenalty(deadwood, CardValuePolicy.ACE_ELEVEN));
        // FACE_SEQUENTIAL: 1 + 5 + 12 + 13 + 25 = 56
        assertEquals(56, calculator.calculateDeadwoodPenalty(deadwood, CardValuePolicy.FACE_SEQUENTIAL));
        // Vacío: 0
        assertEquals(0, calculator.calculateDeadwoodPenalty(List.of(), CardValuePolicy.STANDARD));
    }

    @Test
    void scoreManagerSettlesClassicRoundAndDetectsElimination() {
        IScoreService scoreManager = new ScoreManager();
        scoreManager.registerPlayer("p1");
        scoreManager.registerPlayer("p2");
        scoreManager.registerPlayer("p3");

        GameConfig config = new GameConfig(
                GameMode.CLASSIC, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_51
        );

        // Ronda 1: p1 cierra con -20, p2 tiene 35 pts, p3 tiene 51 pts (eliminado!)
        Map<String, List<Card>> deadwoodRound1 = Map.of(
                "p2", List.of(card(Suit.SPADES, Rank.KING), joker()), // 10 + 25 = 35
                "p3", List.of(
                        card(Suit.HEARTS, Rank.ACE),
                        card(Suit.CLUBS, Rank.FIVE),
                        card(Suit.DIAMONDS, Rank.JACK),
                        card(Suit.SPADES, Rank.QUEEN),
                        joker()
                ) // 1 + 5 + 10 + 10 + 25 = 51
        );

        Map<String, RoundScoreResult> results = scoreManager.settleRound("p1", -20, deadwoodRound1, config);

        assertEquals(-20, results.get("p1").totalScore());
        assertFalse(results.get("p1").eliminated());

        assertEquals(35, results.get("p2").totalScore());
        assertFalse(results.get("p2").eliminated());

        assertEquals(51, results.get("p3").totalScore());
        assertTrue(results.get("p3").eliminated());
        assertTrue(scoreManager.isEliminated("p3"));
        assertFalse(scoreManager.isEliminated("p2"));

        // Ronda 2: p2 suma 20 pts más -> 55 >= 51 (eliminado!)
        RoundScoreResult r2p2 = scoreManager.applyRoundDelta("p2", 20, EliminationLimit.LIMIT_51);
        assertEquals(55, r2p2.totalScore());
        assertTrue(r2p2.eliminated());
        assertTrue(scoreManager.isEliminated("p2"));
    }

    @Test
    void scoreManagerHandlesResetAndUnregisteredPlayers() {
        IScoreService scoreManager = new ScoreManager();
        assertThrows(IllegalArgumentException.class, () -> scoreManager.getScore("unknown"));

        scoreManager.registerPlayer("p1");
        assertEquals(0, scoreManager.getScore("p1"));

        scoreManager.applyRoundDelta("p1", 15, EliminationLimit.LIMIT_101);
        assertEquals(15, scoreManager.getScore("p1"));

        scoreManager.resetScores();
        assertTrue(scoreManager.getAllScores().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(EliminationLimit.class)
    void eliminatesPlayerAtExactLimitAndSupportsNegativeScoreBufferAcrossAllLimits(EliminationLimit limit) {
        IScoreService scoreManager = new ScoreManager();
        scoreManager.registerPlayer("p1");

        // Cierre Clásico negativo (-25 pts) da colchón antes del límite
        RoundScoreResult r1 = scoreManager.applyRoundDelta("p1", -25, limit);
        assertEquals(-25, r1.totalScore());
        assertFalse(r1.eliminated());

        // Queda a 1 punto del límite (limit - 1) -> todavía ACTIVO
        int deltaToJustBelow = (limit.getPoints() - 1) - (-25);
        RoundScoreResult r2 = scoreManager.applyRoundDelta("p1", deltaToJustBelow, limit);
        assertEquals(limit.getPoints() - 1, r2.totalScore());
        assertFalse(r2.eliminated());
        assertFalse(scoreManager.isEliminated("p1"));

        // +1 punto exacto -> alcanza el límite -> ELIMINADO
        RoundScoreResult r3 = scoreManager.applyRoundDelta("p1", 1, limit);
        assertEquals(limit.getPoints(), r3.totalScore());
        assertTrue(r3.eliminated());
        assertTrue(scoreManager.isEliminated("p1"));
    }
}

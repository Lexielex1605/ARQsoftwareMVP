package unit.core;

import app.core.Card;
import app.core.CardBack;
import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.core.GameMode;
import app.core.Rank;
import app.core.Suit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GameConfigAndPoliciesTest {

    @Test
    void cardImagePathsMatchExpectedNamingConvention() {
        Card aceSpades = new Card("D1-SPADES-ACE", Suit.SPADES, Rank.ACE, CardBack.RED, 1);
        Card twoClubs = new Card("D2-CLUBS-TWO", Suit.CLUBS, Rank.TWO, CardBack.BLUE, 2);
        Card tenHearts = new Card("D1-HEARTS-TEN", Suit.HEARTS, Rank.TEN, CardBack.RED, 1);
        Card redJoker = new Card("D1-JOKER-RED", Suit.JOKER, Rank.JOKER, CardBack.RED, 1);
        Card blackJoker = new Card("D2-JOKER-BLACK", Suit.JOKER, Rank.JOKER, CardBack.BLUE, 2);

        assertEquals("/assets/cards/spades/card_spades_A.png", aceSpades.getImagePath());
        assertEquals("/assets/cards/cardback/cardback_red.png", aceSpades.getBackImagePath());
        assertEquals("/assets/cards/clubs/card_clubs_02.png", twoClubs.getImagePath());
        assertEquals("/assets/cards/cardback/cardback_blue.png", twoClubs.getBackImagePath());
        assertEquals("/assets/cards/hearts/card_hearts_10.png", tenHearts.getImagePath());
        assertEquals("/assets/cards/jokers/card_joker_red.png", redJoker.getImagePath());
        assertEquals("/assets/cards/jokers/card_joker_black.png", blackJoker.getImagePath());
    }

    @Test
    void cardValidatesInvariants() {
        assertThrows(IllegalArgumentException.class,
                () -> new Card(" ", Suit.HEARTS, Rank.ACE, CardBack.RED, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Card("D0-HEARTS-ACE", Suit.HEARTS, Rank.ACE, CardBack.RED, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Card("BAD-JOKER-1", Suit.JOKER, Rank.ACE, CardBack.RED, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new Card("BAD-JOKER-2", Suit.SPADES, Rank.JOKER, CardBack.RED, 1));
    }

    @Test
    void cardValuePoliciesEvaluateRanksWithoutConditionals() {
        assertEquals(1, CardValuePolicy.STANDARD.valueOf(Rank.ACE));
        assertEquals(7, CardValuePolicy.STANDARD.valueOf(Rank.SEVEN));
        assertEquals(10, CardValuePolicy.STANDARD.valueOf(Rank.JACK));
        assertEquals(10, CardValuePolicy.STANDARD.valueOf(Rank.QUEEN));
        assertEquals(10, CardValuePolicy.STANDARD.valueOf(Rank.KING));
        assertEquals(25, CardValuePolicy.STANDARD.valueOf(Rank.JOKER));

        assertEquals(10, CardValuePolicy.ACE_TEN.valueOf(Rank.ACE));
        assertEquals(11, CardValuePolicy.ACE_ELEVEN.valueOf(Rank.ACE));

        assertEquals(1, CardValuePolicy.FACE_SEQUENTIAL.valueOf(Rank.ACE));
        assertEquals(11, CardValuePolicy.FACE_SEQUENTIAL.valueOf(Rank.JACK));
        assertEquals(12, CardValuePolicy.FACE_SEQUENTIAL.valueOf(Rank.QUEEN));
        assertEquals(13, CardValuePolicy.FACE_SEQUENTIAL.valueOf(Rank.KING));
    }

    @Test
    void cyclePolicyEvaluatesLinearAndCyclicSequences() {
        List<Rank> aceLow = List.of(Rank.ACE, Rank.TWO, Rank.THREE);
        List<Rank> aceHigh = List.of(Rank.QUEEN, Rank.KING, Rank.ACE);
        List<Rank> wrapAround = List.of(Rank.KING, Rank.ACE, Rank.TWO);
        List<Rank> wrapWithJoker = List.of(Rank.KING, Rank.JOKER, Rank.TWO);
        List<Rank> duplicateRank = List.of(Rank.FIVE, Rank.FIVE, Rank.SIX);

        // LINEAR_ACE_LOW: solo A-2-3
        assertTrue(CyclePolicy.LINEAR_ACE_LOW.isValidSequence(aceLow));
        assertFalse(CyclePolicy.LINEAR_ACE_LOW.isValidSequence(aceHigh));
        assertFalse(CyclePolicy.LINEAR_ACE_LOW.isValidSequence(wrapAround));

        // LINEAR: A-2-3 y Q-K-A, pero nunca K-A-2
        assertTrue(CyclePolicy.LINEAR.isValidSequence(aceLow));
        assertTrue(CyclePolicy.LINEAR.isValidSequence(aceHigh));
        assertFalse(CyclePolicy.LINEAR.isValidSequence(wrapAround));
        assertFalse(CyclePolicy.LINEAR.isValidSequence(wrapWithJoker));

        // CYCLIC: A-2-3, Q-K-A, K-A-2 y K-JOKER-2
        assertTrue(CyclePolicy.CYCLIC.isValidSequence(aceLow));
        assertTrue(CyclePolicy.CYCLIC.isValidSequence(aceHigh));
        assertTrue(CyclePolicy.CYCLIC.isValidSequence(wrapAround));
        assertTrue(CyclePolicy.CYCLIC.isValidSequence(wrapWithJoker));
        assertFalse(CyclePolicy.CYCLIC.isValidSequence(List.of(Rank.QUEEN, Rank.JOKER, Rank.TWO)));
        assertTrue(CyclePolicy.CYCLIC.isValidSequence(List.of(Rank.QUEEN, Rank.JOKER, Rank.JOKER, Rank.TWO)));

        // Duplicados y longitudes fuera de rango
        assertFalse(CyclePolicy.CYCLIC.isValidSequence(duplicateRank));
        assertFalse(CyclePolicy.CYCLIC.isValidSequence(List.of(Rank.ACE, Rank.TWO)));
    }

    @ParameterizedTest
    @CsvSource({"51", "101", "151", "201", "251"})
    void eliminationLimitSupportsConfiguredThresholds(int points) {
        EliminationLimit limit = EliminationLimit.fromPoints(points);
        assertEquals(points, limit.getPoints());
        assertFalse(limit.isEliminated(points - 1));
        assertTrue(limit.isEliminated(points));
    }

    @Test
    void eliminationLimitRejectsUnknownThreshold() {
        assertThrows(IllegalArgumentException.class, () -> EliminationLimit.fromPoints(100));
    }

    @Test
    void gameConfigValidatesDecksAccordingToPlayerCount() {
        GameConfig config = new GameConfig();
        assertDoesNotThrow(config::validate);

        // 2 a 4 jugadores requieren al menos 1 baraja
        for (int players = 2; players <= 4; players++) {
            assertEquals(1, GameConfig.minDecksForPlayers(players));
            config.setMaxPlayers(players);
            config.setDeckCount(1);
            assertTrue(config.isDeckCountSufficient());
            assertDoesNotThrow(config::validate);
        }

        // 5 a 8 jugadores requieren al menos 2 barajas
        for (int players = 5; players <= 8; players++) {
            assertEquals(2, GameConfig.minDecksForPlayers(players));
            config.setMaxPlayers(players);
            config.setDeckCount(1);
            assertFalse(config.isDeckCountSufficient());
            assertThrows(IllegalArgumentException.class, config::validate);

            config.setDeckCount(2);
            assertTrue(config.isDeckCountSufficient());
            assertDoesNotThrow(config::validate);
        }
        // 9 a 12 jugadores requieren al menos 3 barajas
        for (int players = 9; players <= 12; players++) {
            assertEquals(3, GameConfig.minDecksForPlayers(players));
            config.setMaxPlayers(players);
            config.setDeckCount(2);
            assertFalse(config.isDeckCountSufficient());
            assertThrows(IllegalArgumentException.class, config::validate);

            config.setDeckCount(3);
            assertTrue(config.isDeckCountSufficient());
            assertDoesNotThrow(config::validate);
        }

        // 13 a 16 jugadores requieren 4 barajas (4 jugadores por baraja)
        for (int players = 13; players <= 16; players++) {
            assertEquals(4, GameConfig.minDecksForPlayers(players));
            config.setMaxPlayers(players);
            config.setDeckCount(3);
            assertFalse(config.isDeckCountSufficient());
            assertThrows(IllegalArgumentException.class, config::validate);

            config.setDeckCount(4);
            assertTrue(config.isDeckCountSufficient());
            assertDoesNotThrow(config::validate);
        }
    }

    @Test
    void gameConfigRejectsOutOfRangePlayersAndDecks() {
        GameConfig config = new GameConfig();
        assertThrows(IllegalArgumentException.class, () -> config.setMaxPlayers(1));
        assertThrows(IllegalArgumentException.class, () -> config.setMaxPlayers(17));
        assertThrows(IllegalArgumentException.class, () -> config.setDeckCount(0));
        assertThrows(IllegalArgumentException.class, () -> config.setDeckCount(5));
        assertThrows(IllegalArgumentException.class, () -> config.setApuntadoCloseLimit(1));
        assertThrows(IllegalArgumentException.class, () -> config.setApuntadoCloseLimit(27));
        assertDoesNotThrow(() -> config.setApuntadoCloseLimit(8));
        assertEquals(8, config.getApuntadoCloseLimit());

        assertThrows(IllegalArgumentException.class, () -> new GameConfig(
                GameMode.APUNTADO, 6, 1, true,
                CardValuePolicy.STANDARD, CyclePolicy.LINEAR, EliminationLimit.LIMIT_101
        ));

        GameConfig validConfig = new GameConfig(
                GameMode.APUNTADO, 6, 2, false,
                CardValuePolicy.ACE_ELEVEN, CyclePolicy.LINEAR, EliminationLimit.LIMIT_151
        );
        assertDoesNotThrow(() -> validConfig.validateForPlayers(6));
        assertThrows(IllegalArgumentException.class, () -> validConfig.validateForPlayers(7));
    }

    @Test
    void gameModeResolvesCloseModeForClassicApuntadoAndMixed() {
        assertEquals(GameMode.CLASSIC, GameMode.CLASSIC.resolveCloseMode(null));
        assertEquals(GameMode.CLASSIC, GameMode.CLASSIC.resolveCloseMode(GameMode.CLASSIC));
        assertThrows(IllegalArgumentException.class, () -> GameMode.CLASSIC.resolveCloseMode(GameMode.APUNTADO));

        assertEquals(GameMode.APUNTADO, GameMode.APUNTADO.resolveCloseMode(null));
        assertEquals(GameMode.APUNTADO, GameMode.APUNTADO.resolveCloseMode(GameMode.APUNTADO));
        assertThrows(IllegalArgumentException.class, () -> GameMode.APUNTADO.resolveCloseMode(GameMode.CLASSIC));

        assertTrue(GameMode.MIXED.requiresCloseModeSelection());
        assertEquals(GameMode.CLASSIC, GameMode.MIXED.resolveCloseMode(GameMode.CLASSIC));
        assertEquals(GameMode.APUNTADO, GameMode.MIXED.resolveCloseMode(GameMode.APUNTADO));
        assertThrows(IllegalArgumentException.class, () -> GameMode.MIXED.resolveCloseMode(null));
        assertThrows(IllegalArgumentException.class, () -> GameMode.MIXED.resolveCloseMode(GameMode.MIXED));
    }
}

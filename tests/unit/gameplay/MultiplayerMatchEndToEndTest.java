package unit.gameplay;

import app.core.Card;
import app.core.CardBack;
import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.core.GameMode;
import app.core.Rank;
import app.core.Suit;
import app.core.infrastructure.SqliteDatabaseManager;
import app.deck.service.IDeckService;
import app.gameplay.orchestrator.GameOrchestrator;
import app.gameplay.orchestrator.IGameController;
import app.gameplay.orchestrator.PlayerGameView;
import app.gameplay.orchestrator.RoundSummary;
import app.player.JoinResult;
import app.player.MatchStatus;
import app.player.ParticipantStatus;
import app.player.RoomSession;
import app.player.infrastructure.SqlitePlayerRepository;
import app.player.service.IPlayerService;
import app.player.service.PlayerSessionManager;
import app.rules.apuntado.ApuntadoRulesStrategy;
import app.rules.classic.ClassicRulesStrategy;
import app.rules.engine.IRuleEngine;
import app.rules.engine.RuleEngine;
import app.scoring.infrastructure.SqliteScoreRepository;
import app.scoring.service.IScoreRepository;
import app.scoring.service.RoundHistoryEntry;
import app.scoring.service.ScoreManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas end-to-end multijugador completas sobre GameOrchestrator + SQLite real
 * simulando múltiples rondas, múltiples barajas, espejos, turnos completos y eliminaciones hasta campeón.
 */
public class MultiplayerMatchEndToEndTest {

    @TempDir
    Path tempDir;

    private IPlayerService playerService;
    private IScoreRepository scoreRepository;
    private IRuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("e2e_multiplayer.db");
        SqliteDatabaseManager db = new SqliteDatabaseManager(jdbcUrl);
        db.initializeSchema();

        playerService = new PlayerSessionManager(new SqlitePlayerRepository(db));
        scoreRepository = new SqliteScoreRepository(db);
        ruleEngine = new RuleEngine(List.of(new ClassicRulesStrategy(), new ApuntadoRulesStrategy()));
    }

    private static Card card(Suit suit, Rank rank, int deck) {
        return new Card("D" + deck + "-" + suit + "-" + rank, suit, rank, CardBack.forDeckNumber(deck), deck);
    }

    private static List<Card> deadwoodHandApprox55() {
        // 2 juegos armados (0 pts) + 4 sobrantes sin combinación: K♠(10)+Q♥(10)+J♦(10)+9♣(9)+... -> 60 pts exactos en STANDARD
        // Sin combinaciones posibles: 6♠(6), 9♠(9), Q♠(10), 4♥(4), 8♥(8), J♥(10), 2♦(2), 7♦(7), 5♣(5), 3♣(3) = 64 pts
        return List.of(
                card(Suit.SPADES, Rank.SIX, 2),
                card(Suit.SPADES, Rank.NINE, 2),
                card(Suit.SPADES, Rank.QUEEN, 2),
                card(Suit.HEARTS, Rank.FOUR, 2),
                card(Suit.HEARTS, Rank.EIGHT, 2),
                card(Suit.HEARTS, Rank.JACK, 2),
                card(Suit.DIAMONDS, Rank.TWO, 2),
                card(Suit.DIAMONDS, Rank.SEVEN, 2),
                card(Suit.CLUBS, Rank.FIVE, 2),
                card(Suit.CLUBS, Rank.THREE, 2)
        );
    }

    private static List<Card> deadwoodHandApprox35() {
        // 2 ternas armadas (6 cartas) + 4 cartas sueltas: 10♠(10) + 9♥(9) + 8♦(8) + 7♣(7) = 34 pts
        return List.of(
                card(Suit.CLUBS, Rank.TWO, 2),
                card(Suit.CLUBS, Rank.THREE, 2),
                card(Suit.CLUBS, Rank.FOUR, 2),
                card(Suit.DIAMONDS, Rank.FIVE, 2),
                card(Suit.HEARTS, Rank.FIVE, 2),
                card(Suit.SPADES, Rank.FIVE, 2),
                card(Suit.SPADES, Rank.TEN, 2),
                card(Suit.HEARTS, Rank.NINE, 2),
                card(Suit.DIAMONDS, Rank.EIGHT, 2),
                card(Suit.CLUBS, Rank.SEVEN, 2)
        );
    }

    private static List<Card> deadwoodHandApprox15() {
        // 2 ternas armadas (6 cartas) + 4 cartas sueltas: 3♣(3) + 4♦(4) + 2♥(2) + 6♠(6) = 15 pts
        return List.of(
                card(Suit.DIAMONDS, Rank.TEN, 1),
                card(Suit.DIAMONDS, Rank.JACK, 1),
                card(Suit.DIAMONDS, Rank.QUEEN, 1),
                card(Suit.SPADES, Rank.NINE, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.THREE, 2),
                card(Suit.DIAMONDS, Rank.FOUR, 2),
                card(Suit.HEARTS, Rank.TWO, 2),
                card(Suit.SPADES, Rank.SIX, 2)
        );
    }

    @Test
    void playsFullThreePlayerClassicMatchWithTwoDecksAcrossThreeRoundsUntilChampion() {
        // Ronda 1 (3 jugadores: Mateo, Nicole, Sofía):
        // Mateo tiene 4-3-3 (-10); Nicole ~34 pts; Sofía ~64 pts
        List<Card> r1Mateo = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.SPADES, Rank.FIVE, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.DIAMONDS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.KING, 1)
        );
        List<Card> round1 = concat(
                r1Mateo, deadwoodHandApprox35(), deadwoodHandApprox55(),
                List.of(
                        card(Suit.CLUBS, Rank.ACE, 1),   // descarte inicial
                        card(Suit.DIAMONDS, Rank.ACE, 1), // roba Mateo del mazo (y descarta esta misma!)
                        card(Suit.HEARTS, Rank.ACE, 1),   // roba Sofía del mazo
                        card(Suit.SPADES, Rank.ACE, 1)
                )
        );

        // Ronda 2 (3 jugadores, 2 barajas con espejos — caso real de prueba de campo!):
        // Inicia Nicole (rotación), luego Sofía, luego Mateo cierra con espejos 4-3-3 (-10 -> -20)
        // Sofía suma otros 64 -> 128 >= 101 -> ELIMINADA!
        List<Card> r2MateoMirrors = List.of(
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
        List<Card> round2 = concat(
                r2MateoMirrors, deadwoodHandApprox35(), deadwoodHandApprox55(),
                List.of(
                        card(Suit.CLUBS, Rank.ACE, 1),
                        card(Suit.DIAMONDS, Rank.ACE, 1),
                        card(Suit.HEARTS, Rank.ACE, 1)
                )
        );

        // Ronda 3 (solo 2 activos: Mateo y Nicole; Sofía excluida!):
        // Inicia Mateo (cursor 2 % 2 == 0), cierra con escalera completa de 10 (-25 -> -45)
        // Nicole suma 64 -> 34 + 34 + 64 = 132 >= 101 -> ELIMINADA -> Mateo CAMPEÓN!
        List<Card> r3MateoFullStraight = List.of(
                card(Suit.HEARTS, Rank.ACE, 1),
                card(Suit.HEARTS, Rank.TWO, 1),
                card(Suit.HEARTS, Rank.THREE, 1),
                card(Suit.HEARTS, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.FIVE, 1),
                card(Suit.HEARTS, Rank.SIX, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.EIGHT, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.HEARTS, Rank.TEN, 1)
        );
        List<Card> round3 = concat(
                r3MateoFullStraight, deadwoodHandApprox55(),
                List.of(card(Suit.CLUBS, Rank.ACE, 1))
        );

        IGameController controller = new GameOrchestrator(
                () -> new ScriptedMultiRoundDeck(List.of(round1, round2, round3)),
                ruleEngine,
                () -> new ScoreManager(scoreRepository),
                playerService
        );
        GameConfig config = new GameConfig(
                GameMode.CLASSIC, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101
        );

        JoinResult mateo = controller.createRoom("Mateo", config);
        JoinResult nicole = controller.joinRoom(mateo.roomCode(), "Nicole");
        JoinResult sofia = controller.joinRoom(mateo.roomCode(), "Sofía");
        String code = mateo.roomCode();

        controller.startGame(code, mateo.player().getPlayerId());

        // --- RONDA 1: vuelta completa de turnos (robar mazo, robar pozo, descartar) ---
        // Mateo roba del mazo y descarta esa misma carta (jugada válida)
        PlayerGameView mDraw = controller.drawFromDeck(code, mateo.player().getPlayerId());
        String deckCardId = mDraw.viewerHand().get(10).id();
        controller.discard(code, mateo.player().getPlayerId(), deckCardId);

        // Nicole roba del pozo, intenta descartar esa misma (rechazado), descarta otra
        PlayerGameView nDraw = controller.drawFromDiscard(code, nicole.player().getPlayerId());
        assertThrows(IllegalStateException.class,
                () -> controller.discard(code, nicole.player().getPlayerId(), deckCardId));
        controller.discard(code, nicole.player().getPlayerId(), nDraw.viewerHand().get(0).id());

        // Sofía roba del mazo y descarta la misma carta
        PlayerGameView sDraw = controller.drawFromDeck(code, sofia.player().getPlayerId());
        controller.discard(code, sofia.player().getPlayerId(), sDraw.viewerHand().get(10).id());

        // De vuelta en turno de Mateo: declara cierre Regular 4-3-3 (-10 pts)
        RoundSummary r1Summary = controller.declareClose(code, mateo.player().getPlayerId(), null);
        assertEquals(1, r1Summary.roundNumber());
        assertEquals("FOUR_THREE_THREE", r1Summary.closeType());
        assertEquals(-10, r1Summary.closeBonus());
        assertFalse(r1Summary.matchFinished());

        // --- RONDA 2 (con 2 barajas y espejos): inicia Nicole -> Sofía -> cierra Mateo ---
        PlayerGameView r2Start = controller.getPlayerView(code, nicole.player().getPlayerId());
        assertEquals(2, r2Start.roundNumber());
        assertEquals(nicole.player().getPlayerId(), r2Start.currentTurnPlayerId());

        PlayerGameView nR2 = controller.drawFromDeck(code, nicole.player().getPlayerId());
        controller.discard(code, nicole.player().getPlayerId(), nR2.viewerHand().get(10).id());

        PlayerGameView sR2 = controller.drawFromDeck(code, sofia.player().getPlayerId());
        controller.discard(code, sofia.player().getPlayerId(), sR2.viewerHand().get(10).id());

        RoundSummary r2Summary = controller.declareClose(code, mateo.player().getPlayerId(), null);
        assertEquals(2, r2Summary.roundNumber());
        assertEquals("FOUR_THREE_THREE", r2Summary.closeType());
        assertEquals(-10, r2Summary.closeBonus());
        assertFalse(r2Summary.matchFinished());

        // Sofía fue eliminada (>= 101) y ya no recibe cartas en Ronda 3
        RoomSession afterR2 = controller.getRoomSession(code);
        assertEquals(ParticipantStatus.ELIMINADO, afterR2.players().get(2).getStatus());
        assertEquals(0, controller.getPlayerView(code, sofia.player().getPlayerId()).viewerHand().size());

        // --- RONDA 3 (solo Mateo y Nicole): Mateo cierra Full Straight (-25) y gana la partida ---
        PlayerGameView r3View = controller.getPlayerView(code, mateo.player().getPlayerId());
        assertEquals(3, r3View.roundNumber());
        assertEquals(mateo.player().getPlayerId(), r3View.currentTurnPlayerId());

        RoundSummary r3Summary = controller.declareClose(code, mateo.player().getPlayerId(), null);
        assertEquals(3, r3Summary.roundNumber());
        assertEquals("FULL_STRAIGHT", r3Summary.closeType());
        assertEquals(-25, r3Summary.closeBonus());
        assertTrue(r3Summary.matchFinished());
        assertEquals(mateo.player().getPlayerId(), r3Summary.matchWinnerId());

        // Validar persistencia final en SQLite
        RoomSession finalRoom = controller.getRoomSession(code);
        assertEquals(MatchStatus.FINALIZADA, finalRoom.status());
        assertEquals(mateo.player().getPlayerId(), finalRoom.winnerId());
        assertEquals(-45, finalRoom.players().get(0).getAccumulatedScore());
        assertEquals(ParticipantStatus.ACTIVO, finalRoom.players().get(0).getStatus());
        assertEquals(ParticipantStatus.ELIMINADO, finalRoom.players().get(1).getStatus());
        assertEquals(ParticipantStatus.ELIMINADO, finalRoom.players().get(2).getStatus());

        List<RoundHistoryEntry> history = controller.getRoundHistory(code);
        assertEquals(3, history.size());
    }

    @Test
    void playsFullThreePlayerMixedMatchAlternatingRegularAndApuntadoWithCustomLimit() {
        // Modo MIXED con limite_eliminacion = 51 y apuntadoCloseLimit = 7 (permite sobrante 1..6)
        // Ronda 1: Mateo cierra como REGULAR (FOUR_SIX, -15 pts); Nicole +15; Sofía +15
        List<Card> r1MateoFourSix = List.of(
                card(Suit.CLUBS, Rank.FOUR, 1),
                card(Suit.CLUBS, Rank.FIVE, 1),
                card(Suit.CLUBS, Rank.SIX, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.EIGHT, 1),
                card(Suit.CLUBS, Rank.NINE, 1),
                card(Suit.SPADES, Rank.ACE, 1),
                card(Suit.HEARTS, Rank.ACE, 1),
                card(Suit.DIAMONDS, Rank.ACE, 1),
                card(Suit.CLUBS, Rank.ACE, 1)
        );
        List<Card> round1 = concat(
                r1MateoFourSix, deadwoodHandApprox15(), deadwoodHandApprox15(),
                List.of(card(Suit.SPADES, Rank.TWO, 2))
        );

        // Ronda 2 (inicia Nicole): Nicole cierra como APUNTADO (3-3-3 + 6♣ = +6 pts -> 21 pts!)
        // Sofía suma 64 -> 79 >= 51 -> ELIMINADA! Mateo suma 15 -> 0 pts!
        List<Card> r2NicoleApuntadoSix = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.DIAMONDS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.NINE, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.SIX, 1) // 6 pts (< 7 configurado!)
        );
        List<Card> round2 = concat(
                deadwoodHandApprox15(), r2NicoleApuntadoSix, deadwoodHandApprox55(),
                List.of(card(Suit.SPADES, Rank.SEVEN, 2))
        );

        // Ronda 3 (solo Mateo y Nicole; inicia Mateo): Mateo cierra como APUNTADO (3-3-3 + 4♣ = +4 pts -> 4 pts)
        // Nicole suma 34 -> 21 + 34 = 55 >= 51 -> ELIMINADA -> Mateo CAMPEÓN!
        List<Card> r3MateoApuntadoFour = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.FOUR, 1) // 4 pts
        );
        List<Card> round3 = concat(
                r3MateoApuntadoFour, deadwoodHandApprox35(),
                List.of(card(Suit.DIAMONDS, Rank.ACE, 2))
        );

        IGameController controller = new GameOrchestrator(
                () -> new ScriptedMultiRoundDeck(List.of(round1, round2, round3)),
                ruleEngine,
                () -> new ScoreManager(scoreRepository),
                playerService
        );
        GameConfig config = new GameConfig(
                GameMode.MIXED, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_51, 7
        );

        JoinResult mateo = controller.createRoom("Mateo", config);
        JoinResult nicole = controller.joinRoom(mateo.roomCode(), "Nicole");
        controller.joinRoom(mateo.roomCode(), "Sofía");
        String code = mateo.roomCode();

        controller.startGame(code, mateo.player().getPlayerId());

        // Ronda 1: Mateo cierra eligiendo Regular
        RoundSummary s1 = controller.declareClose(code, mateo.player().getPlayerId(), null, GameMode.CLASSIC);
        assertEquals("FOUR_SIX", s1.closeType());
        assertEquals(-15, s1.closeBonus());
        assertFalse(s1.matchFinished());

        // Ronda 2: Nicole cierra eligiendo Apuntado -> se le suman +6 pts del sobrante 6♣ (15 + 6 = 21)
        RoundSummary s2 = controller.declareClose(code, nicole.player().getPlayerId(), null, GameMode.APUNTADO);
        assertEquals("APUNTADO", s2.closeType());
        assertEquals(6, s2.closeBonus());
        assertFalse(s2.matchFinished());
        assertEquals(21, controller.getRoomSession(code).players().get(1).getAccumulatedScore());
        assertEquals(ParticipantStatus.ELIMINADO, controller.getRoomSession(code).players().get(2).getStatus());

        // Ronda 3: Mateo cierra eligiendo Apuntado -> se le suman +4 pts (0 + 4 = 4), Nicole eliminada (55 >= 51)
        RoundSummary s3 = controller.declareClose(code, mateo.player().getPlayerId(), null, GameMode.APUNTADO);
        assertEquals("APUNTADO", s3.closeType());
        assertEquals(4, s3.closeBonus());
        assertTrue(s3.matchFinished());
        assertEquals(mateo.player().getPlayerId(), s3.matchWinnerId());

        RoomSession finalRoom = controller.getRoomSession(code);
        assertEquals(MatchStatus.FINALIZADA, finalRoom.status());
        assertEquals(4, finalRoom.players().get(0).getAccumulatedScore());
        assertEquals(55, finalRoom.players().get(1).getAccumulatedScore());
        assertEquals(3, controller.getRoundHistory(code).size());
    }

    @SafeVarargs
    private static List<Card> concat(List<Card>... lists) {
        List<Card> result = new ArrayList<>();
        for (List<Card> list : lists) {
            result.addAll(list);
        }
        return result;
    }

    private static final class ScriptedMultiRoundDeck implements IDeckService {
        private final List<List<Card>> rounds;
        private int index = -1;
        private final Deque<Card> drawQueue = new ArrayDeque<>();
        private final Deque<Card> discardStack = new ArrayDeque<>();

        ScriptedMultiRoundDeck(List<List<Card>> rounds) {
            this.rounds = rounds;
        }

        @Override
        public void initialize(GameConfig config) {
            loadNextRound();
        }

        @Override
        public void initialize(int deckCount, boolean includeJokers) {
            loadNextRound();
        }

        private void loadNextRound() {
            index = Math.min(index + 1, rounds.size() - 1);
            drawQueue.clear();
            discardStack.clear();
            drawQueue.addAll(rounds.get(index));
        }

        @Override public void shuffle() { }
        @Override public Card drawFromDeck() { return drawQueue.removeFirst(); }
        @Override public Card drawFromDiscard() { return discardStack.pop(); }
        @Override public void discard(Card card) { discardStack.push(card); }
        @Override public Optional<Card> peekDiscard() { return Optional.ofNullable(discardStack.peek()); }
        @Override public Optional<CardBack> peekDeckBack() { return Optional.ofNullable(drawQueue.peek()).map(Card::back); }

        @Override
        public List<Card> dealHand(int cardCount) {
            List<Card> hand = new ArrayList<>(cardCount);
            for (int i = 0; i < cardCount; i++) {
                hand.add(drawFromDeck());
            }
            return hand;
        }

        @Override public boolean recycleDiscardPile() { return false; }
        @Override public int remainingDeckSize() { return drawQueue.size(); }
        @Override public int discardPileSize() { return discardStack.size(); }
    }
}

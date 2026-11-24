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
import app.deck.service.DeckManager;
import app.deck.service.IDeckService;
import app.gameplay.orchestrator.GameOrchestrator;
import app.gameplay.orchestrator.IGameController;
import app.gameplay.orchestrator.LeaveResult;
import app.gameplay.orchestrator.OpponentView;
import app.gameplay.orchestrator.PlayerGameView;
import app.gameplay.orchestrator.RoundSummary;
import app.gameplay.orchestrator.TurnPhase;
import app.player.JoinResult;
import app.player.MatchStatus;
import app.player.ParticipantStatus;
import app.player.Player;
import app.player.infrastructure.SqlitePlayerRepository;
import app.player.service.IPlayerService;
import app.player.service.PlayerSessionManager;
import app.rules.apuntado.ApuntadoRulesStrategy;
import app.rules.classic.ClassicRulesStrategy;
import app.rules.engine.IRuleEngine;
import app.rules.engine.RuleEngine;
import app.scoring.infrastructure.SqliteScoreRepository;
import app.scoring.service.IScoreRepository;
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
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class GameOrchestratorIntegrationTest {

    @TempDir
    Path tempDir;

    private IPlayerService playerService;
    private IScoreRepository scoreRepository;
    private IRuleEngine ruleEngine;

    @BeforeEach
    void setUp() {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("orchestrator_test.db");
        SqliteDatabaseManager db = new SqliteDatabaseManager(jdbcUrl);
        db.initializeSchema();

        playerService = new PlayerSessionManager(new SqlitePlayerRepository(db));
        scoreRepository = new SqliteScoreRepository(db);
        ruleEngine = new RuleEngine(List.of(new ClassicRulesStrategy(), new ApuntadoRulesStrategy()));
    }

    private IGameController createOrchestrator(Supplier<IDeckService> deckFactory) {
        return new GameOrchestrator(
                deckFactory,
                ruleEngine,
                () -> new ScoreManager(scoreRepository),
                playerService
        );
    }

    private static Card card(Suit suit, Rank rank, int deck) {
        return new Card("D" + deck + "-" + suit + "-" + rank, suit, rank, CardBack.forDeckNumber(deck), deck);
    }

    private static Card joker(int deck, String color) {
        return new Card("D" + deck + "-JOKER-" + color, Suit.JOKER, Rank.JOKER, CardBack.forDeckNumber(deck), deck);
    }

    @Test
    void executesFullTurnLifecycleAndFiltersHandsPerViewer() {
        IGameController controller = createOrchestrator(() -> new DeckManager(new Random(42L)));
        GameConfig config = new GameConfig(
                GameMode.CLASSIC, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101
        );

        JoinResult p1 = controller.createRoom("Ana", config);
        JoinResult p2 = controller.joinRoom(p1.roomCode(), "Carlos");

        // No-anfitrión no puede iniciar
        assertThrows(IllegalStateException.class,
                () -> controller.startGame(p1.roomCode(), p2.player().getPlayerId()));

        PlayerGameView startView = controller.startGame(p1.roomCode(), p1.player().getPlayerId());
        assertEquals(MatchStatus.EN_CURSO, startView.matchStatus());
        assertEquals(1, startView.roundNumber());
        assertEquals(p1.player().getPlayerId(), startView.currentTurnPlayerId());
        assertEquals(TurnPhase.MUST_DRAW, startView.turnPhase());
        assertEquals(10, startView.viewerHand().size());
        assertNotNull(startView.topDiscardCard());

        // Vista filtrada: cada jugador solo ve sus propias cartas reales y reversos de oponentes
        PlayerGameView p2View = controller.getPlayerView(p1.roomCode(), p2.player().getPlayerId());
        assertEquals(10, p2View.viewerHand().size());
        OpponentView p1AsSeenByP2 = p2View.players().get(0);
        assertEquals(10, p1AsSeenByP2.cardCount());
        assertEquals(10, p1AsSeenByP2.cardBacks().size());

        // Jugador fuera de turno no puede robar
        assertThrows(IllegalStateException.class,
                () -> controller.drawFromDeck(p1.roomCode(), p2.player().getPlayerId()));

        // P1 no puede descartar antes de robar
        assertThrows(IllegalStateException.class,
                () -> controller.discard(p1.roomCode(), p1.player().getPlayerId(), startView.viewerHand().get(0).id()));

        // P1 roba del mazo (pasa a 11 cartas y fase MUST_DISCARD)
        PlayerGameView afterDraw = controller.drawFromDeck(p1.roomCode(), p1.player().getPlayerId());
        assertEquals(11, afterDraw.viewerHand().size());
        assertEquals(TurnPhase.MUST_DISCARD, afterDraw.turnPhase());

        // P1 descarta una carta -> turno avanza a P2
        Card toDiscard = afterDraw.viewerHand().get(0);
        PlayerGameView afterDiscard = controller.discard(p1.roomCode(), p1.player().getPlayerId(), toDiscard.id());
        assertEquals(10, afterDiscard.viewerHand().size());
        assertEquals(toDiscard, afterDiscard.topDiscardCard());
        assertEquals(p2.player().getPlayerId(), afterDiscard.currentTurnPlayerId());
        assertEquals(TurnPhase.MUST_DRAW, afterDiscard.turnPhase());

        // P2 roba la carta visible del pozo de descarte
        PlayerGameView p2AfterDrawDiscard = controller.drawFromDiscard(p1.roomCode(), p2.player().getPlayerId());
        assertEquals(11, p2AfterDrawDiscard.viewerHand().size());
        assertTrue(p2AfterDrawDiscard.viewerHand().contains(toDiscard));
    }

    @Test
    void settlesClassicCloseEliminatesOpponentAndFinishesMatchInSqlite() {
        List<Card> p1WinningHand = List.of(
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
        // Mano perdedora sin combinaciones posibles: 94 puntos sobrantes (>= 51 -> ELIMINADO)
        List<Card> p2HighPenaltyHand = List.of(
                card(Suit.SPADES, Rank.KING, 2),
                card(Suit.SPADES, Rank.JACK, 2),
                card(Suit.SPADES, Rank.EIGHT, 2),
                card(Suit.HEARTS, Rank.KING, 2),
                card(Suit.HEARTS, Rank.QUEEN, 2),
                card(Suit.HEARTS, Rank.NINE, 2),
                card(Suit.DIAMONDS, Rank.QUEEN, 2),
                card(Suit.DIAMONDS, Rank.JACK, 2),
                card(Suit.DIAMONDS, Rank.SEVEN, 2),
                card(Suit.CLUBS, Rank.TEN, 2)
        );
        Card initialDiscard = card(Suit.CLUBS, Rank.EIGHT, 1);
        Card drawnCard = card(Suit.DIAMONDS, Rank.NINE, 1);

        List<Card> scriptedOrder = new ArrayList<>();
        scriptedOrder.addAll(p1WinningHand);
        scriptedOrder.addAll(p2HighPenaltyHand);
        scriptedOrder.add(initialDiscard);
        scriptedOrder.add(drawnCard);

        IGameController controller = createOrchestrator(() -> new ScriptedDeckService(scriptedOrder));
        GameConfig config = new GameConfig(
                GameMode.CLASSIC, 2, 1, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_51
        );

        JoinResult p1 = controller.createRoom("Campeón", config);
        JoinResult p2 = controller.joinRoom(p1.roomCode(), "Derrotado");
        controller.startGame(p1.roomCode(), p1.player().getPlayerId());

        // P1 declara cierre al inicio de su turno (antes de robar) con su mano ganadora 7+3
        RoundSummary summary = controller.declareClose(p1.roomCode(), p1.player().getPlayerId(), null);

        assertEquals(1, summary.roundNumber());
        assertEquals("SEVEN_THREE", summary.closeType());
        assertEquals(-20, summary.closeBonus());
        assertTrue(summary.matchFinished());
        assertEquals(p1.player().getPlayerId(), summary.matchWinnerId());

        // Verificar persistencia final en SQLite
        assertEquals(MatchStatus.FINALIZADA, controller.getRoomSession(p1.roomCode()).status());
        assertEquals(p1.player().getPlayerId(), controller.getRoomSession(p1.roomCode()).winnerId());
        assertEquals(ParticipantStatus.ELIMINADO,
                controller.getRoomSession(p1.roomCode()).players().get(1).getStatus());
        assertEquals(1, controller.getRoundHistory(p1.roomCode()).size());
    }

    @Test
    void advancesToNextRoundExcludingEliminatedPlayerAndSupportsMidGameReconnect() {
        // 3 jugadores en Apuntado con límite 51:
        // P1 cierra con 3 ternas + 2♣ (< 5 pts)
        // P2 tiene >= 51 pts -> ELIMINADO
        // P3 tiene < 51 pts -> ACTIVO -> inicia Ronda 2 solo con P1 y P3
        List<Card> p1ApuntadoHand = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.TWO, 1)
        );
        // Mano sin combinaciones posibles: 94 puntos sobrantes (>= 51 -> ELIMINADO)
        List<Card> p2EliminatedHand = List.of(
                card(Suit.SPADES, Rank.KING, 2),
                card(Suit.SPADES, Rank.JACK, 2),
                card(Suit.SPADES, Rank.EIGHT, 2),
                card(Suit.HEARTS, Rank.KING, 2),
                card(Suit.HEARTS, Rank.QUEEN, 2),
                card(Suit.HEARTS, Rank.NINE, 2),
                card(Suit.DIAMONDS, Rank.QUEEN, 2),
                card(Suit.DIAMONDS, Rank.JACK, 2),
                card(Suit.DIAMONDS, Rank.SEVEN, 2),
                card(Suit.CLUBS, Rank.TEN, 2)
        );
        // P3 tiene 2 ternas + 4 cartas bajas (3 + 4 + 2 + 5 = 14 pts < 51)
        List<Card> p3SurvivingHand = List.of(
                card(Suit.DIAMONDS, Rank.TEN, 1),
                card(Suit.DIAMONDS, Rank.JACK, 1),
                card(Suit.DIAMONDS, Rank.QUEEN, 1),
                card(Suit.SPADES, Rank.NINE, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.THREE, 2),
                card(Suit.DIAMONDS, Rank.FOUR, 2),
                card(Suit.HEARTS, Rank.TWO, 2),
                card(Suit.SPADES, Rank.FIVE, 2)
        );

        List<Card> round1Cards = new ArrayList<>();
        round1Cards.addAll(p1ApuntadoHand);
        round1Cards.addAll(p2EliminatedHand);
        round1Cards.addAll(p3SurvivingHand);
        round1Cards.add(card(Suit.CLUBS, Rank.EIGHT, 2)); // descarte inicial

        IGameController controller = createOrchestrator(() -> new HybridDeckService(round1Cards));
        GameConfig config = new GameConfig(
                GameMode.APUNTADO, 3, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_51
        );

        JoinResult p1 = controller.createRoom("P1", config);
        JoinResult p2 = controller.joinRoom(p1.roomCode(), "P2");
        JoinResult p3 = controller.joinRoom(p1.roomCode(), "P3");
        controller.startGame(p1.roomCode(), p1.player().getPlayerId());

        // P1 cierra directamente con su mano inicial de 10 cartas (fase MUST_DRAW, sobrante 2♣ -> +2 pts)
        RoundSummary r1 = controller.declareClose(p1.roomCode(), p1.player().getPlayerId(), null);
        assertFalse(r1.matchFinished());
        assertEquals("APUNTADO", r1.closeType());
        assertEquals(2, r1.closeBonus());
        assertEquals(2, controller.getRoomSession(p1.roomCode()).players().get(0).getAccumulatedScore());

        // Verificar que inició Ronda 2, P2 está ELIMINADO sin cartas, y P3 sigue ACTIVO con 10 cartas
        PlayerGameView r2ViewP3 = controller.getPlayerView(p1.roomCode(), p3.player().getPlayerId());
        assertEquals(2, r2ViewP3.roundNumber());
        assertEquals(10, r2ViewP3.viewerHand().size());
        assertEquals(ParticipantStatus.ELIMINADO, r2ViewP3.players().get(1).status());
        assertEquals(0, r2ViewP3.players().get(1).cardCount());

        // Reconexión de P3 en plena Ronda 2
        controller.disconnect(p1.roomCode(), p3.player().getPlayerId());
        Player reconnectedP3 = controller.reconnect(p1.roomCode(), p3.player().getSessionToken());
        assertTrue(reconnectedP3.isConnected());
        PlayerGameView afterReconnect = controller.getPlayerView(p1.roomCode(), p3.player().getPlayerId());
        assertEquals(r2ViewP3.viewerHand(), afterReconnect.viewerHand());
    }

    @Test
    void rejectsInvalidCloseWithoutAlteringHandOrPersistingRound() {
        IGameController controller = createOrchestrator(() -> new DeckManager(new Random(7L)));
        GameConfig config = new GameConfig();
        JoinResult p1 = controller.createRoom("Jugador1", config);
        controller.joinRoom(p1.roomCode(), "Jugador2");

        controller.startGame(p1.roomCode(), p1.player().getPlayerId());
        PlayerGameView afterDraw = controller.drawFromDeck(p1.roomCode(), p1.player().getPlayerId());
        String cardToDiscard = afterDraw.viewerHand().get(0).id();

        assertThrows(IllegalStateException.class,
                () -> controller.declareClose(p1.roomCode(), p1.player().getPlayerId(), cardToDiscard));

        // La mano conserva las 11 cartas, sigue en MUST_DISCARD y 0 rondas persistidas
        PlayerGameView viewAfterFailedClose = controller.getPlayerView(p1.roomCode(), p1.player().getPlayerId());
        assertEquals(11, viewAfterFailedClose.viewerHand().size());
        assertEquals(TurnPhase.MUST_DISCARD, viewAfterFailedClose.turnPhase());
        assertTrue(controller.getRoundHistory(p1.roomCode()).isEmpty());
    }

    @Test
    void supportsMixedModeChoosingClassicInRoundOneAndApuntadoInRoundTwo() {
        // Ronda 1: P1 tiene cierre Regular 7+3 (-20 pts); P2 tiene 14 pts sobrantes (< 51)
        List<Card> r1P1Hand = List.of(
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
        List<Card> r1P2Hand = List.of(
                card(Suit.DIAMONDS, Rank.TEN, 1),
                card(Suit.DIAMONDS, Rank.JACK, 1),
                card(Suit.DIAMONDS, Rank.QUEEN, 1),
                card(Suit.SPADES, Rank.NINE, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.THREE, 2),
                card(Suit.DIAMONDS, Rank.FOUR, 2),
                card(Suit.HEARTS, Rank.TWO, 2),
                card(Suit.SPADES, Rank.FIVE, 2)
        );
        // Ronda 2 (inicia P2 por rotación): P2 tiene cierre Apuntado (3 ternas + 2♣ < 5); P1 tiene 14 pts
        List<Card> r2P1Hand = r1P2Hand;
        List<Card> r2P2Hand = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.TWO, 1)
        );

        List<Card> round1 = new ArrayList<>();
        round1.addAll(r1P1Hand);
        round1.addAll(r1P2Hand);
        round1.add(card(Suit.CLUBS, Rank.EIGHT, 2));

        List<Card> round2 = new ArrayList<>();
        round2.addAll(r2P1Hand);
        round2.addAll(r2P2Hand);
        round2.add(card(Suit.SPADES, Rank.EIGHT, 2));

        IGameController controller = createOrchestrator(() -> new MultiRoundScriptedDeck(List.of(round1, round2)));
        GameConfig config = new GameConfig(
                GameMode.MIXED, 2, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101
        );

        JoinResult p1 = controller.createRoom("Mateo", config);
        JoinResult p2 = controller.joinRoom(p1.roomCode(), "Nicole");
        controller.startGame(p1.roomCode(), p1.player().getPlayerId());

        // Sin elegir modalidad de cierre en modo MIXED -> rechazado
        assertThrows(IllegalArgumentException.class,
                () -> controller.declareClose(p1.roomCode(), p1.player().getPlayerId(), null));

        // Ronda 1: P1 cierra eligiendo Regular (CLASSIC) -> -20 pts
        RoundSummary s1 = controller.declareClose(p1.roomCode(), p1.player().getPlayerId(), null, GameMode.CLASSIC);
        assertEquals("SEVEN_THREE", s1.closeType());
        assertEquals(-20, s1.closeBonus());
        assertFalse(s1.matchFinished());

        // Ronda 2: inicia P2; si intenta cerrar por Regular (CLASSIC) falla porque tiene 2♣ sobrante
        assertThrows(IllegalStateException.class,
                () -> controller.declareClose(p1.roomCode(), p2.player().getPlayerId(), null, GameMode.CLASSIC));

        // P2 cierra eligiendo Apuntado -> éxito (+2 pts de la sobrante 2♣ sumados a sus 14 pts previos = 16)
        RoundSummary s2 = controller.declareClose(p1.roomCode(), p2.player().getPlayerId(), null, GameMode.APUNTADO);
        assertEquals("APUNTADO", s2.closeType());
        assertEquals(2, s2.closeBonus());
        assertEquals(16, controller.getRoomSession(p1.roomCode()).players().get(1).getAccumulatedScore());
        assertEquals(2, controller.getRoundHistory(p1.roomCode()).size());
    }

    @Test
    void handlesPlayerAbandonmentContinuingWithRemainingPlayersAndDeclaringVictoryWhenOneLeft() {
        IGameController controller = createOrchestrator(() -> new DeckManager(new Random(77L)));
        GameConfig config = new GameConfig();
        JoinResult p1 = controller.createRoom("Ana", config);
        JoinResult p2 = controller.joinRoom(p1.roomCode(), "Beto");
        JoinResult p3 = controller.joinRoom(p1.roomCode(), "Cami");
        controller.startGame(p1.roomCode(), p1.player().getPlayerId());

        // Ana (en turno) abandona -> partida continúa con Beto y Cami, turno pasa inmediatamente a Beto
        LeaveResult r1 = controller.leaveRoom(p1.roomCode(), p1.player().getPlayerId());
        assertFalse(r1.matchFinished());
        assertEquals(ParticipantStatus.ABANDONADO,
                controller.getRoomSession(p1.roomCode()).players().get(0).getStatus());

        // Ana ya no puede reconectarse ni volver a unirse
        assertThrows(IllegalStateException.class,
                () -> controller.reconnect(p1.roomCode(), p1.player().getSessionToken()));
        assertThrows(IllegalStateException.class,
                () -> controller.reconnectByPlayerId(p1.roomCode(), p1.player().getPlayerId()));

        PlayerGameView betoView = controller.getPlayerView(p1.roomCode(), p2.player().getPlayerId());
        assertEquals(p2.player().getPlayerId(), betoView.currentTurnPlayerId());
        assertEquals(TurnPhase.MUST_DRAW, betoView.turnPhase());

        // Beto juega un turno normal -> turno pasa a Cami (saltando a Ana)
        PlayerGameView betoDrawn = controller.drawFromDeck(p1.roomCode(), p2.player().getPlayerId());
        PlayerGameView afterBetoDiscard = controller.discard(
                p1.roomCode(), p2.player().getPlayerId(), betoDrawn.viewerHand().get(0).id());
        assertEquals(p3.player().getPlayerId(), afterBetoDiscard.currentTurnPlayerId());

        // Beto también abandona -> solo queda Cami -> Victoria por abandono
        LeaveResult r2 = controller.leaveRoom(p1.roomCode(), p2.player().getPlayerId());
        assertTrue(r2.matchFinished());
        assertEquals(p3.player().getPlayerId(), r2.matchWinnerId());
        assertEquals("VICTORIA_POR_ABANDONO", r2.victorySummary().closeType());
        assertEquals(MatchStatus.FINALIZADA, controller.getRoomSession(p1.roomCode()).status());
        assertEquals(p3.player().getPlayerId(), controller.getRoomSession(p1.roomCode()).winnerId());
    }

    @Test
    void forbidsDiscardingSameCardDrawnFromDiscardButAllowsSameCardFromDeck() {
        IGameController controller = createOrchestrator(() -> new DeckManager(new Random(55L)));
        GameConfig config = new GameConfig();
        JoinResult p1 = controller.createRoom("Mateo", config);
        JoinResult p2 = controller.joinRoom(p1.roomCode(), "Nicole");
        PlayerGameView startView = controller.startGame(p1.roomCode(), p1.player().getPlayerId());

        // 1. P1 roba del POZO de descarte -> NO puede descartar esa misma carta en este turno
        Card takenFromDiscard = startView.topDiscardCard();
        PlayerGameView p1AfterDiscardDraw = controller.drawFromDiscard(p1.roomCode(), p1.player().getPlayerId());
        assertEquals(takenFromDiscard.id(), p1AfterDiscardDraw.forbiddenDiscardCardId());

        assertThrows(IllegalStateException.class,
                () -> controller.discard(p1.roomCode(), p1.player().getPlayerId(), takenFromDiscard.id()));

        // Descartar otra carta distinta sí es válido y pasa el turno a P2
        Card otherCard = p1AfterDiscardDraw.viewerHand().stream()
                .filter(c -> !c.id().equals(takenFromDiscard.id()))
                .findFirst()
                .orElseThrow();
        PlayerGameView afterValidDiscard = controller.discard(
                p1.roomCode(), p1.player().getPlayerId(), otherCard.id());
        assertEquals(p2.player().getPlayerId(), afterValidDiscard.currentTurnPlayerId());

        // 2. P2 roba del MAZO -> SÍ puede descartar esa misma carta en el mismo turno
        List<Card> p2Before = controller.getPlayerView(p1.roomCode(), p2.player().getPlayerId()).viewerHand();
        PlayerGameView p2AfterDeckDraw = controller.drawFromDeck(p1.roomCode(), p2.player().getPlayerId());
        assertEquals(null, p2AfterDeckDraw.forbiddenDiscardCardId());
        Card drawnFromDeck = p2AfterDeckDraw.viewerHand().stream()
                .filter(c -> !p2Before.contains(c))
                .findFirst()
                .orElseThrow();
        PlayerGameView p2AfterSameDiscard = controller.discard(
                p1.roomCode(), p2.player().getPlayerId(), drawnFromDeck.id());
        assertEquals(drawnFromDeck, p2AfterSameDiscard.topDiscardCard());
        assertEquals(p1.player().getPlayerId(), p2AfterSameDiscard.currentTurnPlayerId());
    }

    @Test
    void forbidsDeclaringCloseOnceTurnDrawHasStarted() {
        List<Card> p1WinningHand = List.of(
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
        List<Card> p2Hand = List.of(
                card(Suit.SPADES, Rank.TWO, 2),
                card(Suit.SPADES, Rank.FIVE, 2),
                card(Suit.SPADES, Rank.EIGHT, 2),
                card(Suit.HEARTS, Rank.TWO, 2),
                card(Suit.HEARTS, Rank.TEN, 2),
                card(Suit.DIAMONDS, Rank.ACE, 2),
                card(Suit.DIAMONDS, Rank.FOUR, 2),
                card(Suit.DIAMONDS, Rank.NINE, 2),
                card(Suit.CLUBS, Rank.THREE, 2),
                card(Suit.CLUBS, Rank.SEVEN, 2)
        );
        List<Card> scripted = new ArrayList<>();
        scripted.addAll(p1WinningHand);
        scripted.addAll(p2Hand);
        scripted.add(card(Suit.CLUBS, Rank.EIGHT, 1)); // descarte inicial
        scripted.add(card(Suit.DIAMONDS, Rank.JACK, 1)); // carta robada del mazo

        IGameController controller = createOrchestrator(() -> new ScriptedDeckService(scripted));
        GameConfig config = new GameConfig();
        JoinResult p1 = controller.createRoom("Mateo", config);
        controller.joinRoom(p1.roomCode(), "Nicole");
        controller.startGame(p1.roomCode(), p1.player().getPlayerId());

        // P1 decide robar del mazo -> ya inició la jugada, NO puede declarar cierre en este turno
        PlayerGameView afterDraw = controller.drawFromDeck(p1.roomCode(), p1.player().getPlayerId());
        assertThrows(IllegalStateException.class,
                () -> controller.declareClose(p1.roomCode(), p1.player().getPlayerId(), afterDraw.viewerHand().get(10).id()));
        assertThrows(IllegalStateException.class,
                () -> controller.declareClose(p1.roomCode(), p1.player().getPlayerId(), null));
    }

    @Test
    void addsCloserLeftoverPointsInApuntadoWithConfigurableCloseLimit() {
        // 3 ternas + 7♣ (7 pts sobrante): válido solo porque apuntadoCloseLimit = 8
        List<Card> p1Hand = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.DIAMONDS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.NINE, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.SEVEN, 1)
        );
        List<Card> p2Hand = List.of(
                card(Suit.DIAMONDS, Rank.TEN, 1),
                card(Suit.DIAMONDS, Rank.JACK, 1),
                card(Suit.DIAMONDS, Rank.QUEEN, 1),
                card(Suit.SPADES, Rank.FIVE, 2),
                card(Suit.HEARTS, Rank.FIVE, 2),
                card(Suit.CLUBS, Rank.FIVE, 2),
                card(Suit.CLUBS, Rank.THREE, 2),
                card(Suit.DIAMONDS, Rank.FOUR, 2),
                card(Suit.HEARTS, Rank.TWO, 2),
                card(Suit.SPADES, Rank.SIX, 2)
        );
        List<Card> round1 = new ArrayList<>();
        round1.addAll(p1Hand);
        round1.addAll(p2Hand);
        round1.add(card(Suit.CLUBS, Rank.EIGHT, 2));

        IGameController controller = createOrchestrator(() -> new HybridDeckService(round1));
        GameConfig config = new GameConfig(
                GameMode.APUNTADO, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101, 8
        );

        JoinResult p1 = controller.createRoom("Mateo", config);
        controller.joinRoom(p1.roomCode(), "Nicole");
        controller.startGame(p1.roomCode(), p1.player().getPlayerId());

        RoundSummary summary = controller.declareClose(p1.roomCode(), p1.player().getPlayerId(), null);
        assertEquals("APUNTADO", summary.closeType());
        assertEquals(7, summary.closeBonus());
        assertEquals(7, summary.scores().get(0).roundDelta());
        assertEquals(7, summary.scores().get(0).totalScore());

        // Verificar en vista del jugador y en SQLite
        PlayerGameView view = controller.getPlayerView(p1.roomCode(), p1.player().getPlayerId());
        assertEquals(7, view.players().get(0).accumulatedScore());
        assertEquals(7, controller.getRoomSession(p1.roomCode()).players().get(0).getAccumulatedScore());
    }

    private static final class MultiRoundScriptedDeck implements IDeckService {
        private final List<List<Card>> rounds;
        private int roundIndex = -1;
        private ScriptedDeckService current;

        MultiRoundScriptedDeck(List<List<Card>> rounds) {
            this.rounds = rounds;
        }

        @Override
        public void initialize(GameConfig config) {
            roundIndex = Math.min(roundIndex + 1, rounds.size() - 1);
            current = new ScriptedDeckService(rounds.get(roundIndex));
            current.initialize(config);
        }

        @Override
        public void initialize(int deckCount, boolean includeJokers) {
            roundIndex = Math.min(roundIndex + 1, rounds.size() - 1);
            current = new ScriptedDeckService(rounds.get(roundIndex));
            current.initialize(deckCount, includeJokers);
        }

        @Override public void shuffle() { current.shuffle(); }
        @Override public Card drawFromDeck() { return current.drawFromDeck(); }
        @Override public Card drawFromDiscard() { return current.drawFromDiscard(); }
        @Override public void discard(Card card) { current.discard(card); }
        @Override public Optional<Card> peekDiscard() { return current.peekDiscard(); }
        @Override public Optional<CardBack> peekDeckBack() { return current.peekDeckBack(); }
        @Override public List<Card> dealHand(int cardCount) { return current.dealHand(cardCount); }
        @Override public boolean recycleDiscardPile() { return current.recycleDiscardPile(); }
        @Override public int remainingDeckSize() { return current.remainingDeckSize(); }
        @Override public int discardPileSize() { return current.discardPileSize(); }
    }

    /**
     * Mazo determinista para pruebas de cierre exacto en Ronda 1.
     */
    private static final class ScriptedDeckService implements IDeckService {
        private final List<Card> initialOrder;
        private final Deque<Card> drawQueue = new ArrayDeque<>();
        private final Deque<Card> discardStack = new ArrayDeque<>();

        ScriptedDeckService(List<Card> initialOrder) {
            this.initialOrder = List.copyOf(initialOrder);
        }

        @Override
        public void initialize(GameConfig config) {
            drawQueue.clear();
            discardStack.clear();
            drawQueue.addAll(initialOrder);
        }

        @Override
        public void initialize(int deckCount, boolean includeJokers) {
            drawQueue.clear();
            discardStack.clear();
            drawQueue.addAll(initialOrder);
        }

        @Override
        public void shuffle() {
            // Determinista: sin mezclar
        }

        @Override
        public Card drawFromDeck() {
            return drawQueue.removeFirst();
        }

        @Override
        public Card drawFromDiscard() {
            return discardStack.pop();
        }

        @Override
        public void discard(Card card) {
            discardStack.push(card);
        }

        @Override
        public Optional<Card> peekDiscard() {
            return Optional.ofNullable(discardStack.peek());
        }

        @Override
        public Optional<CardBack> peekDeckBack() {
            return Optional.ofNullable(drawQueue.peek()).map(Card::back);
        }

        @Override
        public List<Card> dealHand(int cardCount) {
            List<Card> hand = new ArrayList<>(cardCount);
            for (int i = 0; i < cardCount; i++) {
                hand.add(drawFromDeck());
            }
            return hand;
        }

        @Override
        public boolean recycleDiscardPile() {
            return false;
        }

        @Override
        public int remainingDeckSize() {
            return drawQueue.size();
        }

        @Override
        public int discardPileSize() {
            return discardStack.size();
        }
    }

    /**
     * Usa cartas deterministas en Ronda 1 y delega a DeckManager real a partir de Ronda 2.
     */
    private static final class HybridDeckService implements IDeckService {
        private final ScriptedDeckService firstRound;
        private final DeckManager realDeck = new DeckManager(new Random(99L));
        private int initCount = 0;

        HybridDeckService(List<Card> round1Cards) {
            this.firstRound = new ScriptedDeckService(round1Cards);
        }

        private IDeckService delegate() {
            return initCount <= 1 ? firstRound : realDeck;
        }

        @Override
        public void initialize(GameConfig config) {
            initCount++;
            delegate().initialize(config);
        }

        @Override
        public void initialize(int deckCount, boolean includeJokers) {
            initCount++;
            delegate().initialize(deckCount, includeJokers);
        }

        @Override
        public void shuffle() {
            delegate().shuffle();
        }

        @Override
        public Card drawFromDeck() {
            return delegate().drawFromDeck();
        }

        @Override
        public Card drawFromDiscard() {
            return delegate().drawFromDiscard();
        }

        @Override
        public void discard(Card card) {
            delegate().discard(card);
        }

        @Override
        public Optional<Card> peekDiscard() {
            return delegate().peekDiscard();
        }

        @Override
        public Optional<CardBack> peekDeckBack() {
            return delegate().peekDeckBack();
        }

        @Override
        public List<Card> dealHand(int cardCount) {
            return delegate().dealHand(cardCount);
        }

        @Override
        public boolean recycleDiscardPile() {
            return delegate().recycleDiscardPile();
        }

        @Override
        public int remainingDeckSize() {
            return delegate().remainingDeckSize();
        }

        @Override
        public int discardPileSize() {
            return delegate().discardPileSize();
        }
    }
}

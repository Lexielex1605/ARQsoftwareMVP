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
import app.core.infrastructure.SqliteDatabaseManager;
import app.player.JoinResult;
import app.player.ParticipantStatus;
import app.player.RoomSession;
import app.player.infrastructure.SqlitePlayerRepository;
import app.player.service.IPlayerService;
import app.player.service.PlayerSessionManager;
import app.scoring.infrastructure.SqliteScoreRepository;
import app.scoring.service.IScoreRepository;
import app.scoring.service.IScoreService;
import app.scoring.service.RoundHistoryEntry;
import app.scoring.service.RoundScoreResult;
import app.scoring.service.ScoreManager;
import app.scoring.service.ScorePersistenceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SqliteScoreTransactionTest {

    @TempDir
    Path tempDir;

    private IPlayerService playerService;
    private IScoreRepository scoreRepository;
    private IScoreService scoreService;

    @BeforeEach
    void setUp() {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("scoring_tx_test.db");
        SqliteDatabaseManager db = new SqliteDatabaseManager(jdbcUrl);
        db.initializeSchema();

        playerService = new PlayerSessionManager(new SqlitePlayerRepository(db));
        scoreRepository = new SqliteScoreRepository(db);
        scoreService = new ScoreManager(scoreRepository);
    }

    private static Card card(Suit suit, Rank rank) {
        return new Card("D1-" + suit + "-" + rank, suit, rank, CardBack.RED, 1);
    }

    private static Card joker(String color) {
        return new Card("D1-JOKER-" + color, Suit.JOKER, Rank.JOKER, CardBack.RED, 1);
    }

    @Test
    void commitsRoundAtomicallyAndMarksEliminatedPlayerInSqlite() {
        GameConfig config = new GameConfig(
                GameMode.CLASSIC, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_51
        );
        JoinResult p1 = playerService.createRoom("Ganador", config);
        JoinResult p2 = playerService.joinRoom(p1.roomCode(), "RivalEliminado");
        JoinResult p3 = playerService.joinRoom(p1.roomCode(), "RivalActivo");

        scoreService.registerPlayer(p1.player().getPlayerId());
        scoreService.registerPlayer(p2.player().getPlayerId());
        scoreService.registerPlayer(p3.player().getPlayerId());

        Map<String, List<Card>> deadwood = new LinkedHashMap<>();
        // 25 + 25 + 10 = 60 >= 51 -> ELIMINADO
        deadwood.put(p2.player().getPlayerId(), List.of(joker("RED"), joker("BLACK"), card(Suit.SPADES, Rank.KING)));
        // 10 + 5 = 15 < 51 -> ACTIVO
        deadwood.put(p3.player().getPlayerId(), List.of(card(Suit.HEARTS, Rank.QUEEN), card(Suit.CLUBS, Rank.FIVE)));

        Map<String, RoundScoreResult> settled = scoreService.settleAndPersistRound(
                p1.matchId(), 1, p1.player().getPlayerId(), "SEVEN_THREE", -20, deadwood, config);

        assertEquals(-20, settled.get(p1.player().getPlayerId()).totalScore());
        assertFalse(settled.get(p1.player().getPlayerId()).eliminated());
        assertEquals(60, settled.get(p2.player().getPlayerId()).totalScore());
        assertTrue(settled.get(p2.player().getPlayerId()).eliminated());
        assertEquals(15, settled.get(p3.player().getPlayerId()).totalScore());
        assertFalse(settled.get(p3.player().getPlayerId()).eliminated());

        // Verificar persistencia real en tablas Ronda, PuntajeRonda y ParticipantePartida
        List<RoundHistoryEntry> history = scoreRepository.findRoundHistory(p1.matchId());
        assertEquals(1, history.size());
        assertEquals("SEVEN_THREE", history.get(0).closeType());
        assertEquals(3, history.get(0).playerScores().size());

        RoomSession reloadedRoom = playerService.getRoom(p1.roomCode());
        assertEquals(-20, reloadedRoom.players().get(0).getAccumulatedScore());
        assertEquals(ParticipantStatus.ACTIVO, reloadedRoom.players().get(0).getStatus());
        assertEquals(60, reloadedRoom.players().get(1).getAccumulatedScore());
        assertEquals(ParticipantStatus.ELIMINADO, reloadedRoom.players().get(1).getStatus());
        assertEquals(15, reloadedRoom.players().get(2).getAccumulatedScore());
        assertEquals(ParticipantStatus.ACTIVO, reloadedRoom.players().get(2).getStatus());
    }

    @Test
    void rollsBackEntireTransactionAndMemoryWhenFailureOccursMidSettlement() {
        GameConfig config = new GameConfig(
                GameMode.CLASSIC, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101
        );
        JoinResult p1 = playerService.createRoom("Ana", config);
        JoinResult p2 = playerService.joinRoom(p1.roomCode(), "Beto");

        scoreService.registerPlayer(p1.player().getPlayerId());
        scoreService.registerPlayer(p2.player().getPlayerId());

        // p2 es válido (se inserta primero) pero "fantasma-id" NO existe en Jugador/ParticipantePartida
        // -> provoca fallo de clave foránea a mitad de la transacción tras insertar Ronda y PuntajeRonda de p1 y p2!
        Map<String, List<Card>> deadwoodWithCorruptPlayer = new LinkedHashMap<>();
        deadwoodWithCorruptPlayer.put(p2.player().getPlayerId(), List.of(card(Suit.SPADES, Rank.KING)));
        deadwoodWithCorruptPlayer.put("fantasma-id-no-existe", List.of(card(Suit.HEARTS, Rank.FIVE)));

        assertThrows(ScorePersistenceException.class, () -> scoreService.settleAndPersistRound(
                p1.matchId(), 1, p1.player().getPlayerId(), "FOUR_THREE_THREE", -10,
                deadwoodWithCorruptPlayer, config
        ));

        // ROLLBACK completo en SQLite: 0 rondas, 0 puntajes de ronda y puntajes acumulados intactos en 0
        assertTrue(scoreRepository.findRoundHistory(p1.matchId()).isEmpty());
        Map<String, Integer> dbScores = scoreRepository.findAccumulatedScores(p1.matchId());
        assertEquals(0, dbScores.get(p1.player().getPlayerId()));
        assertEquals(0, dbScores.get(p2.player().getPlayerId()));

        // Consistencia en memoria: ScoreManager tampoco mutó sus puntajes
        assertEquals(0, scoreService.getScore(p1.player().getPlayerId()));
        assertEquals(0, scoreService.getScore(p2.player().getPlayerId()));
    }
}

package unit.player;

import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.core.GameMode;
import app.core.infrastructure.SqliteDatabaseManager;
import app.player.JoinResult;
import app.player.MatchStatus;
import app.player.ParticipantStatus;
import app.player.Player;
import app.player.RoomSession;
import app.player.infrastructure.SqlitePlayerRepository;
import app.player.service.IPlayerService;
import app.player.service.PlayerSessionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PlayerSessionManagerSqliteTest {

    @TempDir
    Path tempDir;

    private IPlayerService playerService;

    @BeforeEach
    void setUp() {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("players_test.db");
        SqliteDatabaseManager db = new SqliteDatabaseManager(jdbcUrl);
        db.initializeSchema();
        playerService = new PlayerSessionManager(new SqlitePlayerRepository(db));
    }

    @Test
    void createsRoomWithSixCharAlphanumericCodeAndAllowsJoiningUpToLimit() {
        GameConfig config = new GameConfig(
                GameMode.CLASSIC, 3, 1, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101
        );

        JoinResult hostJoin = playerService.createRoom("Ana", config);
        assertEquals(6, hostJoin.roomCode().length());
        assertTrue(hostJoin.roomCode().matches("[A-Z0-9]{6}"));
        assertTrue(hostJoin.player().isHost());
        assertEquals(ParticipantStatus.ACTIVO, hostJoin.player().getStatus());

        JoinResult p2 = playerService.joinRoom(hostJoin.roomCode().toLowerCase(), "Luis");
        JoinResult p3 = playerService.joinRoom(hostJoin.roomCode(), "Sofía");
        assertEquals(1, p2.player().getSeatIndex());
        assertEquals(2, p3.player().getSeatIndex());

        // Cuarto jugador excede maxPlayers = 3
        assertThrows(IllegalStateException.class, () -> playerService.joinRoom(hostJoin.roomCode(), "Carlos"));

        RoomSession session = playerService.getRoom(hostJoin.roomCode());
        assertEquals(MatchStatus.ESPERANDO, session.status());
        assertEquals(3, session.players().size());
    }

    @Test
    void rejectsDuplicatePlayerNamesAndSupportsUpTo16PlayersWithCustomApuntadoLimit() {
        GameConfig config = new GameConfig(
                GameMode.APUNTADO, 16, 4, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101, 8
        );
        JoinResult host = playerService.createRoom("Mateo", config);

        // Nombres duplicados (insensible a mayúsculas y espacios) son rechazados
        assertThrows(IllegalArgumentException.class, () -> playerService.joinRoom(host.roomCode(), "Mateo"));
        assertThrows(IllegalArgumentException.class, () -> playerService.joinRoom(host.roomCode(), "  mateo  "));

        RoomSession reloaded = playerService.getRoom(host.roomCode());
        assertEquals(16, reloaded.config().getMaxPlayers());
        assertEquals(4, reloaded.config().getDeckCount());
        assertEquals(8, reloaded.config().getApuntadoCloseLimit());

        // Unir 15 jugadores más hasta llenar el máximo de 16 (4 por baraja)
        for (int i = 2; i <= 16; i++) {
            playerService.joinRoom(host.roomCode(), "Jugador" + i);
        }
        playerService.validateCanStart(host.roomCode());
        assertEquals(16, playerService.getRoom(host.roomCode()).players().size());

        // El jugador 17 debe ser rechazado
        assertThrows(IllegalStateException.class, () -> playerService.joinRoom(host.roomCode(), "Jugador17"));
    }

    @Test
    void migratesLegacySqliteDatabasePreservingExistingData() throws Exception {
        String legacyUrl = "jdbc:sqlite:" + tempDir.resolve("legacy_migration.db");
        // Simular esquema antiguo (max 8 jugadores, solo CLASSIC/APUNTADO, sin limite_cierre_apuntado ni ABANDONADO)
        try (var conn = java.sql.DriverManager.getConnection(legacyUrl);
             var stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE Jugador (
                        jugador_id TEXT PRIMARY KEY,
                        nombre TEXT NOT NULL,
                        token_sesion TEXT NOT NULL UNIQUE,
                        creado_en TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE Partida (
                        partida_id TEXT PRIMARY KEY,
                        codigo_sala TEXT NOT NULL UNIQUE,
                        modalidad TEXT NOT NULL CHECK (modalidad IN ('CLASSIC', 'APUNTADO')),
                        max_jugadores INTEGER NOT NULL CHECK (max_jugadores BETWEEN 2 AND 8),
                        num_barajas INTEGER NOT NULL CHECK (num_barajas BETWEEN 1 AND 4),
                        incluir_jokers INTEGER NOT NULL,
                        politica_valor TEXT NOT NULL,
                        politica_ciclo TEXT NOT NULL,
                        limite_eliminacion INTEGER NOT NULL,
                        estado TEXT NOT NULL DEFAULT 'ESPERANDO',
                        ganador_id TEXT,
                        creado_en TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        actualizado_en TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE ParticipantePartida (
                        partida_id TEXT NOT NULL,
                        jugador_id TEXT NOT NULL,
                        asiento INTEGER NOT NULL,
                        puntaje_acumulado INTEGER NOT NULL DEFAULT 0,
                        estado TEXT NOT NULL DEFAULT 'ACTIVO' CHECK (estado IN ('ACTIVO', 'ELIMINADO')),
                        conectado INTEGER NOT NULL DEFAULT 1,
                        es_anfitrion INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY (partida_id, jugador_id)
                    )
                    """);
            stmt.execute("INSERT INTO Jugador (jugador_id, nombre, token_sesion) VALUES ('j1', 'LegacyHost', 'tok-1')");
            stmt.execute("""
                    INSERT INTO Partida (
                        partida_id, codigo_sala, modalidad, max_jugadores, num_barajas,
                        incluir_jokers, politica_valor, politica_ciclo, limite_eliminacion, estado
                    ) VALUES ('m1', 'OLD123', 'CLASSIC', 4, 2, 1, 'STANDARD', 'CYCLIC', 101, 'ESPERANDO')
                    """);
            stmt.execute("""
                    INSERT INTO ParticipantePartida (
                        partida_id, jugador_id, asiento, puntaje_acumulado, estado, conectado, es_anfitrion
                    ) VALUES ('m1', 'j1', 0, 0, 'ACTIVO', 1, 1)
                    """);
        }

        SqliteDatabaseManager migratedDb = new SqliteDatabaseManager(legacyUrl);
        migratedDb.initializeSchema();
        IPlayerService migratedService = new PlayerSessionManager(new SqlitePlayerRepository(migratedDb));

        // Datos antiguos preservados con default apuntadoCloseLimit = 5
        RoomSession oldRoom = migratedService.getRoom("OLD123");
        assertEquals(5, oldRoom.config().getApuntadoCloseLimit());
        assertEquals("LegacyHost", oldRoom.players().get(0).getName());

        // Nuevas capacidades funcionan sobre la base migrada (MIXED, 16 jugadores, ABANDONADO)
        GameConfig mixed16 = new GameConfig(
                GameMode.MIXED, 16, 4, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101, 7
        );
        JoinResult newRoom = migratedService.createRoom("NuevoAnfitrion", mixed16);
        assertEquals(GameMode.MIXED, migratedService.getRoom(newRoom.roomCode()).config().getGameMode());
        migratedService.abandonRoom(newRoom.roomCode(), newRoom.player().getPlayerId());
        assertEquals(ParticipantStatus.ABANDONADO,
                migratedService.getRoom(newRoom.roomCode()).players().get(0).getStatus());
    }

    @Test
    void validatesMinimumPlayersAndDeckCountBeforeStarting() {
        GameConfig config = new GameConfig(
                GameMode.APUNTADO, 6, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.LINEAR, EliminationLimit.LIMIT_101
        );
        JoinResult host = playerService.createRoom("Anfitrión", config);

        // Con solo 1 jugador no se puede iniciar (mínimo 2)
        assertThrows(IllegalArgumentException.class, () -> playerService.validateCanStart(host.roomCode()));

        // Con 2 jugadores y 2 barajas sí se puede iniciar
        playerService.joinRoom(host.roomCode(), "Invitado");
        playerService.validateCanStart(host.roomCode());

        // Configuración inválida: 5 jugadores con 1 baraja es rechazada
        GameConfig invalidDecks = new GameConfig();
        invalidDecks.setMaxPlayers(5);
        invalidDecks.setDeckCount(1);
        assertThrows(IllegalArgumentException.class, () -> playerService.createRoom("MalConfig", invalidDecks));
    }

    @Test
    void supportsDisconnectReconnectAndParticipantStatusUpdates() {
        GameConfig config = new GameConfig();
        JoinResult host = playerService.createRoom("Mateo", config);
        JoinResult guest = playerService.joinRoom(host.roomCode(), "Laura");

        // Desconexión y reconexión por token de sesión
        playerService.disconnect(host.roomCode(), guest.player().getPlayerId());
        RoomSession afterDisconnect = playerService.getRoom(host.roomCode());
        assertFalse(afterDisconnect.players().get(1).isConnected());

        Player reconnected = playerService.reconnect(host.roomCode(), guest.player().getSessionToken());
        assertTrue(reconnected.isConnected());
        assertEquals(guest.player().getPlayerId(), reconnected.getPlayerId());

        // Token inválido rechazado
        assertThrows(IllegalArgumentException.class,
                () -> playerService.reconnect(host.roomCode(), "token-inexistente"));

        // Cambio de estado ACTIVO -> ELIMINADO
        playerService.markPlayerEliminated(host.roomCode(), guest.player().getPlayerId());
        assertEquals(1, playerService.getActivePlayers(host.roomCode()).size());
        assertEquals(ParticipantStatus.ELIMINADO,
                playerService.getRoom(host.roomCode()).players().get(1).getStatus());
    }
}

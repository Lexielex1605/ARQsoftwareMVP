package app.player.infrastructure;

import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.core.GameMode;
import app.core.infrastructure.SqliteDatabaseManager;
import app.player.MatchStatus;
import app.player.ParticipantStatus;
import app.player.Player;
import app.player.RoomSession;
import app.player.service.IPlayerRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Repositorio SQLite para gestión transaccional de Jugador, Partida y ParticipantePartida.
 */
public final class SqlitePlayerRepository implements IPlayerRepository {

    private final SqliteDatabaseManager database;

    public SqlitePlayerRepository(SqliteDatabaseManager database) {
        this.database = Objects.requireNonNull(database, "El gestor SQLite no puede ser nulo");
    }

    @Override
    public void createMatchWithHost(String matchId, String roomCode, GameConfig config, Player host) {
        try (Connection conn = database.openConnection()) {
            conn.setAutoCommit(false);
            try {
                insertPlayer(conn, host);
                insertMatch(conn, matchId, roomCode, config);
                insertParticipant(conn, matchId, host);
                conn.commit();
            } catch (SQLException ex) {
                SqliteDatabaseManager.rollbackQuietly(conn);
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Error creando partida en SQLite: " + roomCode, ex);
        }
    }

    @Override
    public void addParticipant(String matchId, Player player) {
        try (Connection conn = database.openConnection()) {
            conn.setAutoCommit(false);
            try {
                insertPlayer(conn, player);
                insertParticipant(conn, matchId, player);
                conn.commit();
            } catch (SQLException ex) {
                SqliteDatabaseManager.rollbackQuietly(conn);
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Error registrando participante en partida " + matchId, ex);
        }
    }

    @Override
    public boolean roomCodeExists(String roomCode) {
        String sql = "SELECT 1 FROM Partida WHERE codigo_sala = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Error consultando existencia de sala " + roomCode, ex);
        }
    }

    @Override
    public Optional<RoomSession> findRoomByCode(String roomCode) {
        String sql = """
                SELECT partida_id, codigo_sala, modalidad, max_jugadores, num_barajas,
                       incluir_jokers, politica_valor, politica_ciclo, limite_eliminacion,
                       limite_cierre_apuntado, estado, ganador_id
                FROM Partida
                WHERE codigo_sala = ?
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, roomCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String matchId = rs.getString("partida_id");
                GameConfig config = mapConfig(rs);
                MatchStatus status = MatchStatus.valueOf(rs.getString("estado"));
                String winnerId = rs.getString("ganador_id");
                List<Player> players = loadParticipants(conn, matchId);
                return Optional.of(new RoomSession(matchId, roomCode, config, status, winnerId, players));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Error cargando sala " + roomCode, ex);
        }
    }

    @Override
    public Optional<Player> findPlayerBySessionToken(String roomCode, String sessionToken) {
        String sql = """
                SELECT j.jugador_id, j.nombre, j.token_sesion,
                       pp.asiento, pp.puntaje_acumulado, pp.estado, pp.conectado, pp.es_anfitrion
                FROM ParticipantePartida pp
                JOIN Jugador j ON j.jugador_id = pp.jugador_id
                JOIN Partida p ON p.partida_id = pp.partida_id
                WHERE p.codigo_sala = ? AND j.token_sesion = ?
                """;
        return querySinglePlayer(sql, roomCode, sessionToken);
    }

    @Override
    public Optional<Player> findPlayerById(String roomCode, String playerId) {
        String sql = """
                SELECT j.jugador_id, j.nombre, j.token_sesion,
                       pp.asiento, pp.puntaje_acumulado, pp.estado, pp.conectado, pp.es_anfitrion
                FROM ParticipantePartida pp
                JOIN Jugador j ON j.jugador_id = pp.jugador_id
                JOIN Partida p ON p.partida_id = pp.partida_id
                WHERE p.codigo_sala = ? AND j.jugador_id = ?
                """;
        return querySinglePlayer(sql, roomCode, playerId);
    }

    @Override
    public void updateConnectionStatus(String matchId, String playerId, boolean connected) {
        String sql = "UPDATE ParticipantePartida SET conectado = ? WHERE partida_id = ? AND jugador_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, connected ? 1 : 0);
            ps.setString(2, matchId);
            ps.setString(3, playerId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Error actualizando estado de conexión del jugador " + playerId, ex);
        }
    }

    @Override
    public void updateParticipantStatus(String matchId, String playerId, ParticipantStatus status) {
        String sql = "UPDATE ParticipantePartida SET estado = ? WHERE partida_id = ? AND jugador_id = ?";
        try (Connection conn = database.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, matchId);
            ps.setString(3, playerId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Error actualizando estado del participante " + playerId, ex);
        }
    }

    @Override
    public void markParticipantAbandoned(String matchId, String playerId, String newHostPlayerId) {
        String abandonSql = """
                UPDATE ParticipantePartida
                SET estado = 'ABANDONADO', conectado = 0, es_anfitrion = 0
                WHERE partida_id = ? AND jugador_id = ?
                """;
        String promoteHostSql = """
                UPDATE ParticipantePartida
                SET es_anfitrion = 1
                WHERE partida_id = ? AND jugador_id = ?
                """;
        try (Connection conn = database.openConnection()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(abandonSql)) {
                    ps.setString(1, matchId);
                    ps.setString(2, playerId);
                    ps.executeUpdate();
                }
                if (newHostPlayerId != null) {
                    try (PreparedStatement ps = conn.prepareStatement(promoteHostSql)) {
                        ps.setString(1, matchId);
                        ps.setString(2, newHostPlayerId);
                        ps.executeUpdate();
                    }
                }
                conn.commit();
            } catch (SQLException ex) {
                SqliteDatabaseManager.rollbackQuietly(conn);
                throw ex;
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Error registrando abandono del jugador " + playerId, ex);
        }
    }

    @Override
    public void updateMatchStatus(String matchId, MatchStatus status, String winnerId) {
        String sql = """
                UPDATE Partida
                SET estado = ?, ganador_id = ?, actualizado_en = CURRENT_TIMESTAMP
                WHERE partida_id = ?
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status.name());
            ps.setString(2, winnerId);
            ps.setString(3, matchId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Error actualizando estado de partida " + matchId, ex);
        }
    }

    @Override
    public void updateRoomConfig(String matchId, GameConfig config) {
        String sql = """
                UPDATE Partida
                SET modalidad = ?, max_jugadores = ?, num_barajas = ?, incluir_jokers = ?,
                    politica_valor = ?, politica_ciclo = ?, limite_eliminacion = ?,
                    limite_cierre_apuntado = ?, actualizado_en = CURRENT_TIMESTAMP
                WHERE partida_id = ?
                """;
        try (Connection conn = database.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, config.getGameMode().name());
            ps.setInt(2, config.getMaxPlayers());
            ps.setInt(3, config.getDeckCount());
            ps.setInt(4, config.isIncludeJokers() ? 1 : 0);
            ps.setString(5, config.getCardValuePolicy().name());
            ps.setString(6, config.getCyclePolicy().name());
            ps.setInt(7, config.getEliminationLimit().getPoints());
            ps.setInt(8, config.getApuntadoCloseLimit());
            ps.setString(9, matchId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException("Error actualizando configuración de partida " + matchId, ex);
        }
    }

    private void insertPlayer(Connection conn, Player player) throws SQLException {
        String sql = "INSERT INTO Jugador (jugador_id, nombre, token_sesion) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, player.getPlayerId());
            ps.setString(2, player.getName());
            ps.setString(3, player.getSessionToken());
            ps.executeUpdate();
        }
    }

    private void insertMatch(Connection conn, String matchId, String roomCode, GameConfig config)
            throws SQLException {
        String sql = """
                INSERT INTO Partida (
                    partida_id, codigo_sala, modalidad, max_jugadores, num_barajas,
                    incluir_jokers, politica_valor, politica_ciclo, limite_eliminacion,
                    limite_cierre_apuntado, estado
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchId);
            ps.setString(2, roomCode);
            ps.setString(3, config.getGameMode().name());
            ps.setInt(4, config.getMaxPlayers());
            ps.setInt(5, config.getDeckCount());
            ps.setInt(6, config.isIncludeJokers() ? 1 : 0);
            ps.setString(7, config.getCardValuePolicy().name());
            ps.setString(8, config.getCyclePolicy().name());
            ps.setInt(9, config.getEliminationLimit().getPoints());
            ps.setInt(10, config.getApuntadoCloseLimit());
            ps.setString(11, MatchStatus.ESPERANDO.name());
            ps.executeUpdate();
        }
    }

    private void insertParticipant(Connection conn, String matchId, Player player) throws SQLException {
        String sql = """
                INSERT INTO ParticipantePartida (
                    partida_id, jugador_id, asiento, puntaje_acumulado, estado, conectado, es_anfitrion
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchId);
            ps.setString(2, player.getPlayerId());
            ps.setInt(3, player.getSeatIndex());
            ps.setInt(4, player.getAccumulatedScore());
            ps.setString(5, player.getStatus().name());
            ps.setInt(6, player.isConnected() ? 1 : 0);
            ps.setInt(7, player.isHost() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    private List<Player> loadParticipants(Connection conn, String matchId) throws SQLException {
        String sql = """
                SELECT j.jugador_id, j.nombre, j.token_sesion,
                       pp.asiento, pp.puntaje_acumulado, pp.estado, pp.conectado, pp.es_anfitrion
                FROM ParticipantePartida pp
                JOIN Jugador j ON j.jugador_id = pp.jugador_id
                WHERE pp.partida_id = ?
                ORDER BY pp.asiento ASC
                """;
        List<Player> players = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    players.add(mapPlayer(rs));
                }
            }
        }
        return players;
    }

    private Optional<Player> querySinglePlayer(String sql, String param1, String param2) {
        try (Connection conn = database.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param1);
            ps.setString(2, param2);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(mapPlayer(rs));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Error consultando jugador en SQLite", ex);
        }
    }

    private GameConfig mapConfig(ResultSet rs) throws SQLException {
        return new GameConfig(
                GameMode.valueOf(rs.getString("modalidad")),
                rs.getInt("max_jugadores"),
                rs.getInt("num_barajas"),
                rs.getInt("incluir_jokers") == 1,
                CardValuePolicy.valueOf(rs.getString("politica_valor")),
                CyclePolicy.valueOf(rs.getString("politica_ciclo")),
                EliminationLimit.fromPoints(rs.getInt("limite_eliminacion")),
                rs.getInt("limite_cierre_apuntado")
        );
    }

    private Player mapPlayer(ResultSet rs) throws SQLException {
        return new Player(
                rs.getString("jugador_id"),
                rs.getString("nombre"),
                rs.getString("token_sesion"),
                rs.getInt("asiento"),
                rs.getInt("puntaje_acumulado"),
                ParticipantStatus.valueOf(rs.getString("estado")),
                rs.getInt("conectado") == 1,
                rs.getInt("es_anfitrion") == 1
        );
    }
}

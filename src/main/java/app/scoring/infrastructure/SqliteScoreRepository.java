package app.scoring.infrastructure;

import app.core.infrastructure.SqliteDatabaseManager;
import app.scoring.service.IScoreRepository;
import app.scoring.service.RoundHistoryEntry;
import app.scoring.service.RoundScoreResult;
import app.scoring.service.ScorePersistenceException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repositorio SQLite transaccional (ACID: BEGIN / COMMIT / ROLLBACK) para el cierre de rondas.
 * Solo depende de app.core.infrastructure y app.scoring.service (cumple RG-03).
 */
public final class SqliteScoreRepository implements IScoreRepository {

    private static final String STATUS_ACTIVE = "ACTIVO";
    private static final String STATUS_ELIMINATED = "ELIMINADO";

    private final SqliteDatabaseManager database;

    public SqliteScoreRepository(SqliteDatabaseManager database) {
        this.database = Objects.requireNonNull(database, "El gestor SQLite no puede ser nulo");
    }

    @Override
    public void persistRoundSettlement(
            String matchId,
            int roundNumber,
            String winnerId,
            String closeType,
            int winnerBonus,
            Collection<RoundScoreResult> results
    ) {
        Objects.requireNonNull(matchId, "El matchId no puede ser nulo");
        Objects.requireNonNull(winnerId, "El winnerId no puede ser nulo");
        Objects.requireNonNull(closeType, "El tipo de cierre no puede ser nulo");
        Objects.requireNonNull(results, "Los resultados de ronda no pueden ser nulos");

        try (Connection conn = database.openConnection()) {
            conn.setAutoCommit(false);
            try {
                long roundId = insertRound(conn, matchId, roundNumber, winnerId, closeType, winnerBonus);
                for (RoundScoreResult result : results) {
                    String status = result.eliminated() ? STATUS_ELIMINATED : STATUS_ACTIVE;
                    insertRoundScore(conn, roundId, result, status);
                    updateParticipantScore(conn, matchId, result, status);
                }
                conn.commit();
            } catch (SQLException ex) {
                SqliteDatabaseManager.rollbackQuietly(conn);
                throw new ScorePersistenceException(
                        "Transacción abortada (ROLLBACK) al liquidar ronda " + roundNumber
                                + " de partida " + matchId, ex);
            } finally {
                conn.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw new ScorePersistenceException("Error de conexión SQLite al liquidar ronda " + roundNumber, ex);
        }
    }

    @Override
    public List<RoundHistoryEntry> findRoundHistory(String matchId) {
        String sql = """
                SELECT ronda_id, numero_ronda, ganador_id, tipo_cierre, bono_cierre
                FROM Ronda
                WHERE partida_id = ?
                ORDER BY numero_ronda ASC
                """;
        List<RoundHistoryEntry> history = new ArrayList<>();
        try (Connection conn = database.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long roundId = rs.getLong("ronda_id");
                    List<RoundScoreResult> scores = loadRoundScores(conn, roundId);
                    history.add(new RoundHistoryEntry(
                            rs.getInt("numero_ronda"),
                            rs.getString("ganador_id"),
                            rs.getString("tipo_cierre"),
                            rs.getInt("bono_cierre"),
                            scores
                    ));
                }
            }
            return history;
        } catch (SQLException ex) {
            throw new ScorePersistenceException("Error leyendo historial de rondas para partida " + matchId, ex);
        }
    }

    @Override
    public Map<String, Integer> findAccumulatedScores(String matchId) {
        String sql = """
                SELECT jugador_id, puntaje_acumulado
                FROM ParticipantePartida
                WHERE partida_id = ?
                ORDER BY asiento ASC
                """;
        Map<String, Integer> scores = new LinkedHashMap<>();
        try (Connection conn = database.openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    scores.put(rs.getString("jugador_id"), rs.getInt("puntaje_acumulado"));
                }
            }
            return scores;
        } catch (SQLException ex) {
            throw new ScorePersistenceException("Error leyendo puntajes acumulados de partida " + matchId, ex);
        }
    }

    private long insertRound(
            Connection conn,
            String matchId,
            int roundNumber,
            String winnerId,
            String closeType,
            int winnerBonus
    ) throws SQLException {
        String sql = """
                INSERT INTO Ronda (partida_id, numero_ronda, ganador_id, tipo_cierre, bono_cierre)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, matchId);
            ps.setInt(2, roundNumber);
            ps.setString(3, winnerId);
            ps.setString(4, closeType);
            ps.setInt(5, winnerBonus);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new SQLException("No se generó ronda_id para la ronda " + roundNumber);
                }
                return keys.getLong(1);
            }
        }
    }

    private void insertRoundScore(
            Connection conn,
            long roundId,
            RoundScoreResult result,
            String status
    ) throws SQLException {
        String sql = """
                INSERT INTO PuntajeRonda (
                    ronda_id, jugador_id, delta_puntos, puntaje_acumulado, estado_resultante
                ) VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, roundId);
            ps.setString(2, result.playerId());
            ps.setInt(3, result.roundDelta());
            ps.setInt(4, result.totalScore());
            ps.setString(5, status);
            ps.executeUpdate();
        }
    }

    private void updateParticipantScore(
            Connection conn,
            String matchId,
            RoundScoreResult result,
            String status
    ) throws SQLException {
        String sql = """
                UPDATE ParticipantePartida
                SET puntaje_acumulado = ?, estado = ?
                WHERE partida_id = ? AND jugador_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, result.totalScore());
            ps.setString(2, status);
            ps.setString(3, matchId);
            ps.setString(4, result.playerId());
            int affected = ps.executeUpdate();
            if (affected != 1) {
                throw new SQLException(
                        "Participante inexistente en partida " + matchId + ": " + result.playerId());
            }
        }
    }

    private List<RoundScoreResult> loadRoundScores(Connection conn, long roundId) throws SQLException {
        String sql = """
                SELECT jugador_id, delta_puntos, puntaje_acumulado, estado_resultante
                FROM PuntajeRonda
                WHERE ronda_id = ?
                ORDER BY puntaje_ronda_id ASC
                """;
        List<RoundScoreResult> scores = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, roundId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    scores.add(new RoundScoreResult(
                            rs.getString("jugador_id"),
                            rs.getInt("delta_puntos"),
                            rs.getInt("puntaje_acumulado"),
                            STATUS_ELIMINATED.equals(rs.getString("estado_resultante"))
                    ));
                }
            }
        }
        return scores;
    }
}

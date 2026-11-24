package app.scoring.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Puerto de persistencia transaccional para rondas, puntajes por ronda y puntajes acumulados.
 */
public interface IScoreRepository {

    /**
     * Persiste atómicamente (BEGIN / COMMIT / ROLLBACK) la ronda cerrada,
     * los deltas de PuntajeRonda y la actualización de puntaje acumulado y estado
     * (ACTIVO / ELIMINADO) en ParticipantePartida.
     */
    void persistRoundSettlement(
            String matchId,
            int roundNumber,
            String winnerId,
            String closeType,
            int winnerBonus,
            Collection<RoundScoreResult> results
    );

    List<RoundHistoryEntry> findRoundHistory(String matchId);

    Map<String, Integer> findAccumulatedScores(String matchId);
}

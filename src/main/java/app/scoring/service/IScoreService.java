package app.scoring.service;

import app.core.Card;
import app.core.CardValuePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;

import java.util.List;
import java.util.Map;

/**
 * Servicio de gestión de puntajes acumulados, penalizaciones, persistencia transaccional y eliminación.
 */
public interface IScoreService {

    void registerPlayer(String playerId);

    int getScore(String playerId);

    Map<String, Integer> getAllScores();

    boolean isEliminated(String playerId);

    int calculatePenalty(List<Card> deadwoodCards, CardValuePolicy policy);

    RoundScoreResult applyRoundDelta(String playerId, int roundDelta, EliminationLimit limit);

    /**
     * Liquida una ronda en memoria: aplica el bono (o 0) al ganador y suma la penalización
     * de cartas sobrantes a cada oponente, evaluando el límite de eliminación configurado.
     */
    Map<String, RoundScoreResult> settleRound(
            String winnerPlayerId,
            int winnerBonus,
            Map<String, List<Card>> opponentDeadwood,
            GameConfig config
    );

    /**
     * Liquida y persiste atómicamente una ronda (BEGIN / COMMIT / ROLLBACK en SQLite).
     * Si la persistencia falla, no se muta el estado en memoria.
     */
    Map<String, RoundScoreResult> settleAndPersistRound(
            String matchId,
            int roundNumber,
            String winnerPlayerId,
            String closeType,
            int winnerBonus,
            Map<String, List<Card>> opponentDeadwood,
            GameConfig config
    );

    List<RoundHistoryEntry> getRoundHistory(String matchId);

    void resetScores();
}

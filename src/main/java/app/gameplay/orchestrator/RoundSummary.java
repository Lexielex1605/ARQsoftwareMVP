package app.gameplay.orchestrator;

import app.scoring.service.RoundScoreResult;

import java.util.List;
import java.util.Objects;

/**
 * Resumen completo de una ronda cerrada y liquidada.
 */
public record RoundSummary(
        int roundNumber,
        String winnerPlayerId,
        String closeType,
        int closeBonus,
        List<RoundScoreResult> scores,
        boolean matchFinished,
        String matchWinnerId
) {
    public RoundSummary {
        Objects.requireNonNull(winnerPlayerId, "El winnerPlayerId no puede ser nulo");
        Objects.requireNonNull(closeType, "El tipo de cierre no puede ser nulo");
        Objects.requireNonNull(scores, "Los puntajes no pueden ser nulos");
        scores = List.copyOf(scores);
    }
}

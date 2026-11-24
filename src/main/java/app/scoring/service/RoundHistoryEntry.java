package app.scoring.service;

import java.util.List;
import java.util.Objects;

/**
 * Registro histórico persistido de una ronda finalizada y sus puntajes resultantes.
 */
public record RoundHistoryEntry(
        int roundNumber,
        String winnerId,
        String closeType,
        int winnerBonus,
        List<RoundScoreResult> playerScores
) {
    public RoundHistoryEntry {
        Objects.requireNonNull(winnerId, "El winnerId no puede ser nulo");
        Objects.requireNonNull(closeType, "El tipo de cierre no puede ser nulo");
        Objects.requireNonNull(playerScores, "Los puntajes de la ronda no pueden ser nulos");
        playerScores = List.copyOf(playerScores);
    }
}

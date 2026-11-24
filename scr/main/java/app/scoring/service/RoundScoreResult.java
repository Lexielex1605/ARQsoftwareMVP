package app.scoring.service;

import java.util.Objects;

/**
 * Resultado del cálculo de puntaje de un jugador al cierre de una ronda.
 */
public record RoundScoreResult(
        String playerId,
        int roundDelta,
        int totalScore,
        boolean eliminated
) {
    public RoundScoreResult {
        Objects.requireNonNull(playerId, "El playerId no puede ser nulo");
        if (playerId.isBlank()) {
            throw new IllegalArgumentException("El playerId no puede estar vacío");
        }
    }
}

package app.player;

import app.core.GameConfig;

import java.util.Objects;

/**
 * Resultado de crear una sala o unirse a una existente.
 */
public record JoinResult(
        String matchId,
        String roomCode,
        Player player,
        GameConfig config
) {
    public JoinResult {
        Objects.requireNonNull(matchId, "El matchId no puede ser nulo");
        Objects.requireNonNull(roomCode, "El código de sala no puede ser nulo");
        Objects.requireNonNull(player, "El jugador no puede ser nulo");
        Objects.requireNonNull(config, "La configuración no puede ser nula");
    }
}

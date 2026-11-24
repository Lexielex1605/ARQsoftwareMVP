package app.player;

import app.core.GameConfig;

import java.util.List;
import java.util.Objects;

/**
 * Instantánea persistida de una sala y sus participantes.
 */
public record RoomSession(
        String matchId,
        String roomCode,
        GameConfig config,
        MatchStatus status,
        String winnerId,
        List<Player> players
) {
    public RoomSession {
        Objects.requireNonNull(matchId, "El matchId no puede ser nulo");
        Objects.requireNonNull(roomCode, "El código de sala no puede ser nulo");
        Objects.requireNonNull(config, "La configuración no puede ser nula");
        Objects.requireNonNull(status, "El estado de partida no puede ser nulo");
        Objects.requireNonNull(players, "La lista de jugadores no puede ser nula");
        players = List.copyOf(players);
    }
}

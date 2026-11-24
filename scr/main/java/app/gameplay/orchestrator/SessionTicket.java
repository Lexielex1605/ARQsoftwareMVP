package app.gameplay.orchestrator;

import app.core.GameConfig;

import java.util.Objects;

/**
 * Ticket de sesión devuelto al crear, unirse o reconectarse a una sala.
 * Permite a la capa de transporte operar sin importar tipos internos de app.player.
 */
public record SessionTicket(
        String matchId,
        String roomCode,
        String playerId,
        String playerName,
        String sessionToken,
        int seatIndex,
        boolean host,
        GameConfig config
) {
    public SessionTicket {
        Objects.requireNonNull(matchId, "matchId no puede ser nulo");
        Objects.requireNonNull(roomCode, "roomCode no puede ser nulo");
        Objects.requireNonNull(playerId, "playerId no puede ser nulo");
        Objects.requireNonNull(playerName, "playerName no puede ser nulo");
        Objects.requireNonNull(sessionToken, "sessionToken no puede ser nulo");
        Objects.requireNonNull(config, "config no puede ser nula");
    }
}

package app.gameplay.orchestrator;

import app.core.GameConfig;

import java.util.List;
import java.util.Objects;

/**
 * Resumen público de una sala para consultas HTTP y validación previa al ingreso.
 */
public record RoomOverview(
        String matchId,
        String roomCode,
        String status,
        GameConfig config,
        int playerCount,
        int maxPlayers,
        boolean canStart,
        List<OpponentView> players
) {
    public RoomOverview {
        Objects.requireNonNull(matchId, "matchId no puede ser nulo");
        Objects.requireNonNull(roomCode, "roomCode no puede ser nulo");
        Objects.requireNonNull(status, "status no puede ser nulo");
        Objects.requireNonNull(config, "config no puede ser nula");
        Objects.requireNonNull(players, "players no puede ser nula");
        players = List.copyOf(players);
    }
}

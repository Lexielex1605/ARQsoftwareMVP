package app.gameplay.orchestrator;

import java.util.Objects;

/**
 * Resultado del abandono definitivo de un jugador de una sala o partida en curso.
 */
public record LeaveResult(
        String roomCode,
        String leftPlayerId,
        String leftPlayerName,
        boolean matchFinished,
        String matchWinnerId,
        String matchWinnerName,
        String message,
        RoundSummary victorySummary
) {
    public LeaveResult {
        Objects.requireNonNull(roomCode, "roomCode no puede ser nulo");
        Objects.requireNonNull(leftPlayerId, "leftPlayerId no puede ser nulo");
        Objects.requireNonNull(leftPlayerName, "leftPlayerName no puede ser nulo");
        Objects.requireNonNull(message, "message no puede ser nulo");
    }
}

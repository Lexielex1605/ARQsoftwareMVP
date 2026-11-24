package app.gameplay.orchestrator;

import app.core.Card;
import app.core.CardBack;
import app.core.GameConfig;
import app.player.MatchStatus;

import java.util.List;
import java.util.Objects;

/**
 * Vista filtrada del estado de la partida para un jugador concreto.
 * Solo el propietario recibe sus cartas reales en {@code viewerHand}.
 */
public record PlayerGameView(
        String matchId,
        String roomCode,
        MatchStatus matchStatus,
        GameConfig config,
        int roundNumber,
        String currentTurnPlayerId,
        TurnPhase turnPhase,
        int remainingDeckSize,
        CardBack topDeckBack,
        Card topDiscardCard,
        String viewerPlayerId,
        List<Card> viewerHand,
        String forbiddenDiscardCardId,
        List<OpponentView> players,
        String winnerPlayerId,
        RoundSummary lastRoundSummary
) {
    public PlayerGameView {
        Objects.requireNonNull(matchId, "El matchId no puede ser nulo");
        Objects.requireNonNull(roomCode, "El código de sala no puede ser nulo");
        Objects.requireNonNull(matchStatus, "El estado de partida no puede ser nulo");
        Objects.requireNonNull(config, "La configuración no puede ser nula");
        Objects.requireNonNull(turnPhase, "La fase de turno no puede ser nula");
        Objects.requireNonNull(viewerPlayerId, "El viewerPlayerId no puede ser nulo");
        Objects.requireNonNull(viewerHand, "La mano del jugador no puede ser nula");
        Objects.requireNonNull(players, "La lista de jugadores no puede ser nula");
        viewerHand = List.copyOf(viewerHand);
        players = List.copyOf(players);
    }
}

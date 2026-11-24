package app.gameplay.orchestrator;

import app.core.CardBack;
import app.player.ParticipantStatus;

import java.util.List;
import java.util.Objects;

/**
 * Vista segura de un jugador para otros participantes: solo expone cantidad de cartas
 * y colores de reverso (rojo/azul), nunca las caras reales de las cartas.
 */
public record OpponentView(
        String playerId,
        String name,
        int seatIndex,
        int accumulatedScore,
        ParticipantStatus status,
        boolean connected,
        boolean host,
        int cardCount,
        List<CardBack> cardBacks
) {
    public OpponentView {
        Objects.requireNonNull(playerId, "El playerId no puede ser nulo");
        Objects.requireNonNull(name, "El nombre no puede ser nulo");
        Objects.requireNonNull(status, "El estado no puede ser nulo");
        Objects.requireNonNull(cardBacks, "Los reversos no pueden ser nulos");
        cardBacks = List.copyOf(cardBacks);
    }
}

package app.player;

import java.util.Objects;

/**
 * Entidad inmutable que representa un jugador participante dentro de una partida.
 */
public final class Player {

    private final String playerId;
    private final String name;
    private final String sessionToken;
    private final int seatIndex;
    private final int accumulatedScore;
    private final ParticipantStatus status;
    private final boolean connected;
    private final boolean host;

    public Player(String name) {
        this("legacy-" + name, name, "legacy-token-" + name, 0, 0, ParticipantStatus.ACTIVO, true, false);
    }

    public Player(
            String playerId,
            String name,
            String sessionToken,
            int seatIndex,
            int accumulatedScore,
            ParticipantStatus status,
            boolean connected,
            boolean host
    ) {
        this.playerId = requireNonBlank(playerId, "El playerId no puede ser vacío");
        this.name = requireNonBlank(name, "El nombre del jugador no puede ser vacío");
        this.sessionToken = requireNonBlank(sessionToken, "El token de sesión no puede ser vacío");
        if (seatIndex < 0) {
            throw new IllegalArgumentException("El asiento no puede ser negativo: " + seatIndex);
        }
        this.seatIndex = seatIndex;
        this.accumulatedScore = accumulatedScore;
        this.status = Objects.requireNonNull(status, "El estado del participante no puede ser nulo");
        this.connected = connected;
        this.host = host;
    }

    public String getPlayerId() {
        return playerId;
    }

    public String getName() {
        return name;
    }

    public String getSessionToken() {
        return sessionToken;
    }

    public int getSeatIndex() {
        return seatIndex;
    }

    public int getAccumulatedScore() {
        return accumulatedScore;
    }

    public ParticipantStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == ParticipantStatus.ACTIVO;
    }

    public boolean isEliminated() {
        return status == ParticipantStatus.ELIMINADO;
    }

    public boolean isAbandoned() {
        return status == ParticipantStatus.ABANDONADO;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isHost() {
        return host;
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}

package app.infrastructure.web.dto;

import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.GameConfig;
import app.core.GameMode;

/**
 * Solicitud HTTP del anfitrión para actualizar GameConfig antes de iniciar la partida.
 */
public record UpdateConfigRequest(
        String requesterPlayerId,
        GameMode gameMode,
        Integer maxPlayers,
        Integer deckCount,
        Boolean includeJokers,
        CardValuePolicy cardValuePolicy,
        CyclePolicy cyclePolicy,
        Integer eliminationLimit,
        Integer apuntadoCloseLimit
) {
    public GameConfig toGameConfig() {
        return new CreateRoomRequest(
                "host", gameMode, maxPlayers, deckCount,
                includeJokers, cardValuePolicy, cyclePolicy, eliminationLimit, apuntadoCloseLimit
        ).toGameConfig();
    }
}

package app.infrastructure.web.dto;

import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.core.GameMode;

/**
 * Solicitud HTTP para crear una nueva sala con configuración personalizable.
 */
public record CreateRoomRequest(
        String hostName,
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
        GameConfig config = new GameConfig();
        if (gameMode != null) {
            config.setGameMode(gameMode);
        }
        if (maxPlayers != null) {
            config.setMaxPlayers(maxPlayers);
        }
        if (deckCount != null) {
            config.setDeckCount(deckCount);
        }
        if (includeJokers != null) {
            config.setIncludeJokers(includeJokers);
        }
        if (cardValuePolicy != null) {
            config.setCardValuePolicy(cardValuePolicy);
        }
        if (cyclePolicy != null) {
            config.setCyclePolicy(cyclePolicy);
        }
        if (eliminationLimit != null) {
            config.setEliminationLimit(EliminationLimit.fromPoints(eliminationLimit));
        }
        if (apuntadoCloseLimit != null) {
            config.setApuntadoCloseLimit(apuntadoCloseLimit);
        }
        return config;
    }
}

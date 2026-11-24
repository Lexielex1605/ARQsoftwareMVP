package app.infrastructure.web.dto;

/**
 * Sobre JSON entrante por WebSocket (/ws/{roomCode}).
 * Eventos soportados: JOIN, RECONNECT, START_GAME, DRAW_DECK, DRAW_DISCARD,
 * DISCARD, DECLARE_CLOSE (con closeMode opcional/obligatorio en modo MIXED), SYNC_STATE.
 */
public record WsInboundMessage(
        String type,
        String playerId,
        String sessionToken,
        String playerName,
        String cardId,
        String closeMode
) {
}

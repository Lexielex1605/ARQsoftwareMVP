package app.infrastructure.web.dto;

/**
 * Sobre JSON saliente por WebSocket hacia los clientes conectados.
 */
public record WsOutboundMessage(
        String type,
        Object payload,
        String error
) {
    public static WsOutboundMessage of(String type, Object payload) {
        return new WsOutboundMessage(type, payload, null);
    }

    public static WsOutboundMessage error(String message) {
        return new WsOutboundMessage("ERROR", null, message);
    }
}

package app.infrastructure.web.dto;

/**
 * Solicitud HTTP para reconectarse a una sala usando token de sesión o playerId.
 */
public record ReconnectRequest(String sessionToken, String playerId) {
}

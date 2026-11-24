package app.infrastructure.web.dto;

/**
 * Solicitud HTTP para unirse a una sala existente por código de 6 dígitos.
 */
public record JoinRoomRequest(String playerName) {
}

package app.gameplay.orchestrator;

import app.core.GameConfig;
import app.core.GameMode;
import app.player.JoinResult;
import app.player.Player;
import app.player.RoomSession;
import app.scoring.service.RoundHistoryEntry;

import java.util.List;

/**
 * Interfaz única de comandos del juego consumida por controladores REST/WebSocket y UI (RG-01).
 */
public interface IGameController {

    JoinResult createRoom(String hostName, GameConfig config);

    JoinResult joinRoom(String roomCode, String playerName);

    SessionTicket createRoomSession(String hostName, GameConfig config);

    SessionTicket joinRoomSession(String roomCode, String playerName);

    SessionTicket reconnectSession(String roomCode, String sessionToken, String playerId);

    RoomOverview getRoomOverview(String roomCode);

    Player reconnect(String roomCode, String sessionToken);

    Player reconnectByPlayerId(String roomCode, String playerId);

    void disconnect(String roomCode, String playerId);

    /**
     * Registra el abandono definitivo de un jugador: no podrá volver a unirse.
     * Si la partida está en curso continúa con el resto y, si solo queda 1 activo, declara victoria por abandono.
     */
    LeaveResult leaveRoom(String roomCode, String playerId);

    void updateConfig(String roomCode, String requesterPlayerId, GameConfig newConfig);

    PlayerGameView startGame(String roomCode, String requesterPlayerId);

    PlayerGameView drawFromDeck(String roomCode, String playerId);

    PlayerGameView drawFromDiscard(String roomCode, String playerId);

    PlayerGameView discard(String roomCode, String playerId, String cardId);

    /**
     * Declara cierre en el turno del jugador.
     * Si el jugador está en fase MUST_DISCARD (11 cartas), debe indicar {@code optionalDiscardCardId}
     * para validar las 10 cartas restantes antes de confirmar el descarte y liquidar la ronda.
     * Si está en fase MUST_DRAW (10 cartas), {@code optionalDiscardCardId} puede ser {@code null}.
     */
    RoundSummary declareClose(String roomCode, String playerId, String optionalDiscardCardId);

    /**
     * Declara cierre indicando opcionalmente la modalidad concreta de cierre (CLASSIC o APUNTADO),
     * requerida cuando la partida está en modo MIXED.
     */
    RoundSummary declareClose(
            String roomCode,
            String playerId,
            String optionalDiscardCardId,
            GameMode requestedCloseMode
    );

    PlayerGameView getPlayerView(String roomCode, String viewerPlayerId);

    RoomSession getRoomSession(String roomCode);

    List<RoundHistoryEntry> getRoundHistory(String roomCode);
}

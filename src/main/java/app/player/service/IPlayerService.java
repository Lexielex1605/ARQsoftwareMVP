package app.player.service;

import app.core.GameConfig;
import app.player.JoinResult;
import app.player.Player;
import app.player.RoomSession;

import java.util.List;

/**
 * Servicio de gestión de salas, sesiones de jugadores, reconexión y estados ACTIVO/ELIMINADO.
 */
public interface IPlayerService {

    JoinResult createRoom(String hostName, GameConfig config);

    JoinResult joinRoom(String roomCode, String playerName);

    Player reconnect(String roomCode, String sessionToken);

    Player reconnectByPlayerId(String roomCode, String playerId);

    void disconnect(String roomCode, String playerId);

    Player abandonRoom(String roomCode, String playerId);

    RoomSession getRoom(String roomCode);

    void updateConfig(String roomCode, String requesterPlayerId, GameConfig newConfig);

    void validateCanStart(String roomCode);

    void markMatchStarted(String roomCode);

    void markMatchFinished(String roomCode, String winnerId);

    void markPlayerEliminated(String roomCode, String playerId);

    List<Player> getActivePlayers(String roomCode);
}

package app.player.service;

import app.core.GameConfig;
import app.player.MatchStatus;
import app.player.ParticipantStatus;
import app.player.Player;
import app.player.RoomSession;

import java.util.Optional;

/**
 * Puerto de persistencia para salas, jugadores y participantes.
 */
public interface IPlayerRepository {

    void createMatchWithHost(String matchId, String roomCode, GameConfig config, Player host);

    void addParticipant(String matchId, Player player);

    boolean roomCodeExists(String roomCode);

    Optional<RoomSession> findRoomByCode(String roomCode);

    Optional<Player> findPlayerBySessionToken(String roomCode, String sessionToken);

    Optional<Player> findPlayerById(String roomCode, String playerId);

    void updateConnectionStatus(String matchId, String playerId, boolean connected);

    void updateParticipantStatus(String matchId, String playerId, ParticipantStatus status);

    void markParticipantAbandoned(String matchId, String playerId, String newHostPlayerId);

    void updateMatchStatus(String matchId, MatchStatus status, String winnerId);

    void updateRoomConfig(String matchId, GameConfig config);
}

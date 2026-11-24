package app.player.service;

import app.core.GameConfig;
import app.player.JoinResult;
import app.player.MatchStatus;
import app.player.ParticipantStatus;
import app.player.Player;
import app.player.RoomSession;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Implementación del servicio de sesiones de jugador y gestión de salas.
 * Genera códigos alfanuméricos de 6 dígitos, valida reglas de capacidad/barajas y soporta reconexión.
 */
public final class PlayerSessionManager implements IPlayerService {

    public static final int ROOM_CODE_LENGTH = 6;
    private static final String ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int MAX_CODE_GENERATION_ATTEMPTS = 20;

    private final IPlayerRepository repository;
    private final Random random;

    public PlayerSessionManager(IPlayerRepository repository) {
        this(repository, new SecureRandom());
    }

    public PlayerSessionManager(IPlayerRepository repository, Random random) {
        this.repository = Objects.requireNonNull(repository, "El repositorio de jugadores no puede ser nulo");
        this.random = Objects.requireNonNull(random, "El generador aleatorio no puede ser nulo");
    }

    @Override
    public JoinResult createRoom(String hostName, GameConfig config) {
        String cleanName = requireNonBlank(hostName, "El nombre del anfitrión no puede estar vacío");
        Objects.requireNonNull(config, "La configuración no puede ser nula");
        config.validate();

        String matchId = UUID.randomUUID().toString();
        String roomCode = generateUniqueRoomCode();
        Player host = new Player(
                UUID.randomUUID().toString(),
                cleanName,
                UUID.randomUUID().toString(),
                0,
                0,
                ParticipantStatus.ACTIVO,
                true,
                true
        );

        repository.createMatchWithHost(matchId, roomCode, config, host);
        return new JoinResult(matchId, roomCode, host, config);
    }

    @Override
    public JoinResult joinRoom(String roomCode, String playerName) {
        String normalizedCode = normalizeRoomCode(roomCode);
        String cleanName = requireNonBlank(playerName, "El nombre del jugador no puede estar vacío");
        RoomSession room = getRoom(normalizedCode);

        if (room.status() != MatchStatus.ESPERANDO) {
            throw new IllegalStateException("La partida en la sala " + normalizedCode + " ya inició o finalizó");
        }
        boolean duplicateName = room.players().stream()
                .anyMatch(existing -> existing.getName().equalsIgnoreCase(cleanName));
        if (duplicateName) {
            throw new IllegalArgumentException(
                    "Ya existe un jugador con el nombre '" + cleanName + "' en la sala " + normalizedCode);
        }
        long activeCount = room.players().stream().filter(Player::isActive).count();
        if (activeCount >= room.config().getMaxPlayers()) {
            throw new IllegalStateException(
                    "La sala " + normalizedCode + " alcanzó su máximo de " + room.config().getMaxPlayers() + " jugadores");
        }

        int nextSeat = room.players().size();
        Player participant = new Player(
                UUID.randomUUID().toString(),
                cleanName,
                UUID.randomUUID().toString(),
                nextSeat,
                0,
                ParticipantStatus.ACTIVO,
                true,
                false
        );

        repository.addParticipant(room.matchId(), participant);
        return new JoinResult(room.matchId(), room.roomCode(), participant, room.config());
    }

    @Override
    public Player reconnect(String roomCode, String sessionToken) {
        String normalizedCode = normalizeRoomCode(roomCode);
        String cleanToken = requireNonBlank(sessionToken, "El token de sesión no puede estar vacío");
        RoomSession room = getRoom(normalizedCode);

        Player player = repository.findPlayerBySessionToken(normalizedCode, cleanToken)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Token de sesión inválido para la sala " + normalizedCode));
        verifyNotAbandoned(player);

        repository.updateConnectionStatus(room.matchId(), player.getPlayerId(), true);
        return repository.findPlayerById(normalizedCode, player.getPlayerId()).orElse(player);
    }

    @Override
    public Player reconnectByPlayerId(String roomCode, String playerId) {
        String normalizedCode = normalizeRoomCode(roomCode);
        String cleanPlayerId = requireNonBlank(playerId, "El playerId no puede estar vacío");
        RoomSession room = getRoom(normalizedCode);

        Player player = repository.findPlayerById(normalizedCode, cleanPlayerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Jugador no pertenece a la sala " + normalizedCode + ": " + cleanPlayerId));
        verifyNotAbandoned(player);

        repository.updateConnectionStatus(room.matchId(), player.getPlayerId(), true);
        return repository.findPlayerById(normalizedCode, player.getPlayerId()).orElse(player);
    }

    @Override
    public void disconnect(String roomCode, String playerId) {
        String normalizedCode = normalizeRoomCode(roomCode);
        RoomSession room = getRoom(normalizedCode);
        repository.updateConnectionStatus(room.matchId(), playerId, false);
    }

    @Override
    public Player abandonRoom(String roomCode, String playerId) {
        String normalizedCode = normalizeRoomCode(roomCode);
        String cleanPlayerId = requireNonBlank(playerId, "El playerId no puede estar vacío");
        RoomSession room = getRoom(normalizedCode);

        Player leaving = room.players().stream()
                .filter(p -> p.getPlayerId().equals(cleanPlayerId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Jugador no pertenece a la sala " + normalizedCode + ": " + cleanPlayerId));

        String newHostId = null;
        if (leaving.isHost()) {
            newHostId = room.players().stream()
                    .filter(p -> !p.getPlayerId().equals(cleanPlayerId) && p.isActive())
                    .map(Player::getPlayerId)
                    .findFirst()
                    .orElse(null);
        }

        repository.markParticipantAbandoned(room.matchId(), cleanPlayerId, newHostId);
        return repository.findPlayerById(normalizedCode, cleanPlayerId).orElse(leaving);
    }

    private static void verifyNotAbandoned(Player player) {
        if (player.isAbandoned()) {
            throw new IllegalStateException("Abandonaste esta partida y no puedes volver a unirte");
        }
    }

    @Override
    public RoomSession getRoom(String roomCode) {
        String normalizedCode = normalizeRoomCode(roomCode);
        return repository.findRoomByCode(normalizedCode)
                .orElseThrow(() -> new IllegalArgumentException("Sala no encontrada: " + normalizedCode));
    }

    @Override
    public void updateConfig(String roomCode, String requesterPlayerId, GameConfig newConfig) {
        Objects.requireNonNull(newConfig, "La nueva configuración no puede ser nula");
        RoomSession room = getRoom(roomCode);
        validateHostUpdate(room, requesterPlayerId, newConfig);
        repository.updateRoomConfig(room.matchId(), newConfig);
    }

    @Override
    public void validateCanStart(String roomCode) {
        RoomSession room = getRoom(roomCode);
        if (room.status() != MatchStatus.ESPERANDO) {
            throw new IllegalStateException("La partida ya no está en estado ESPERANDO");
        }
        int activeCount = (int) room.players().stream().filter(Player::isActive).count();
        room.config().validateForPlayers(activeCount);
    }

    @Override
    public void markMatchStarted(String roomCode) {
        RoomSession room = getRoom(roomCode);
        repository.updateMatchStatus(room.matchId(), MatchStatus.EN_CURSO, null);
    }

    @Override
    public void markMatchFinished(String roomCode, String winnerId) {
        RoomSession room = getRoom(roomCode);
        repository.updateMatchStatus(room.matchId(), MatchStatus.FINALIZADA, winnerId);
    }

    @Override
    public void markPlayerEliminated(String roomCode, String playerId) {
        RoomSession room = getRoom(roomCode);
        repository.updateParticipantStatus(room.matchId(), playerId, ParticipantStatus.ELIMINADO);
    }

    @Override
    public List<Player> getActivePlayers(String roomCode) {
        return getRoom(roomCode).players().stream()
                .filter(Player::isActive)
                .toList();
    }

    private void validateHostUpdate(RoomSession room, String requesterPlayerId, GameConfig newConfig) {
        if (room.status() != MatchStatus.ESPERANDO) {
            throw new IllegalStateException("Solo se puede modificar la configuración antes de iniciar la partida");
        }
        boolean isHost = room.players().stream()
                .anyMatch(p -> p.getPlayerId().equals(requesterPlayerId) && p.isHost());
        if (!isHost) {
            throw new IllegalStateException("Solo el anfitrión puede modificar la configuración de la sala");
        }
        newConfig.validate();
        if (newConfig.getMaxPlayers() < room.players().size()) {
            throw new IllegalArgumentException(
                    "El máximo de jugadores no puede ser menor a los ya unidos (" + room.players().size() + ")");
        }
    }

    private String generateUniqueRoomCode() {
        for (int attempt = 0; attempt < MAX_CODE_GENERATION_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!repository.roomCodeExists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No fue posible generar un código de sala único");
    }

    private String randomCode() {
        StringBuilder code = new StringBuilder(ROOM_CODE_LENGTH);
        for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
            code.append(ROOM_CODE_ALPHABET.charAt(random.nextInt(ROOM_CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    private static String normalizeRoomCode(String roomCode) {
        String clean = requireNonBlank(roomCode, "El código de sala no puede estar vacío").toUpperCase(Locale.ROOT);
        if (clean.length() != ROOM_CODE_LENGTH) {
            throw new IllegalArgumentException("El código de sala debe tener 6 caracteres, recibido: " + clean);
        }
        return clean;
    }

    private static String requireNonBlank(String value, String message) {
        Objects.requireNonNull(value, message);
        if (value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}

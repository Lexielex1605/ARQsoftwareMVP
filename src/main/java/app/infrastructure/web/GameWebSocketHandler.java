package app.infrastructure.web;

import app.core.GameMode;
import app.gameplay.orchestrator.IGameController;
import app.gameplay.orchestrator.LeaveResult;
import app.gameplay.orchestrator.PlayerGameView;
import app.gameplay.orchestrator.RoundSummary;
import app.gameplay.orchestrator.SessionTicket;
import app.infrastructure.web.dto.WsInboundMessage;
import app.infrastructure.web.dto.WsOutboundMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.websocket.WsCloseContext;
import io.javalin.websocket.WsConnectContext;
import io.javalin.websocket.WsContext;
import io.javalin.websocket.WsErrorContext;
import io.javalin.websocket.WsMessageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Adaptador WebSocket (/ws/{roomCode}) que traduce eventos JSON hacia IGameController
 * y difunde vistas filtradas (GAME_STATE) a cada jugador conectado (RG-01).
 */
public final class GameWebSocketHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GameWebSocketHandler.class);

    private final IGameController gameController;
    private final ObjectMapper objectMapper;
    private final Map<String, Map<String, WsContext>> roomSessions = new ConcurrentHashMap<>();
    private final Map<String, BoundSession> sessionIndex = new ConcurrentHashMap<>();
    private final Map<String, BiConsumer<WsMessageContext, WsInboundMessage>> eventHandlers;

    public GameWebSocketHandler(IGameController gameController, ObjectMapper objectMapper) {
        this.gameController = Objects.requireNonNull(gameController, "gameController no puede ser nulo");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper no puede ser nulo");
        this.eventHandlers = buildEventHandlers();
    }

    public void registerRoutes(Javalin app) {
        app.ws("/ws/{roomCode}", ws -> {
            ws.onConnect(this::onConnect);
            ws.onMessage(this::onMessage);
            ws.onClose(this::onClose);
            ws.onError(this::onError);
        });
    }

    private Map<String, BiConsumer<WsMessageContext, WsInboundMessage>> buildEventHandlers() {
        return Map.of(
                "JOIN", this::handleJoin,
                "RECONNECT", this::handleReconnect,
                "START_GAME", this::handleStartGame,
                "DRAW_DECK", this::handleDrawDeck,
                "DRAW_DISCARD", this::handleDrawDiscard,
                "DISCARD", this::handleDiscard,
                "DECLARE_CLOSE", this::handleDeclareClose,
                "LEAVE_ROOM", this::handleLeaveRoom,
                "SYNC_STATE", this::handleSyncState
        );
    }

    private void onConnect(WsConnectContext ctx) {
        ctx.enableAutomaticPings();
        LOGGER.debug("WS conectado a sala {} (sessionId={})", normalizeRoomCode(ctx), ctx.sessionId());
    }

    private void onMessage(WsMessageContext ctx) {
        try {
            WsInboundMessage message = objectMapper.readValue(ctx.message(), WsInboundMessage.class);
            dispatchMessage(ctx, message);
        } catch (JsonProcessingException ex) {
            send(ctx, WsOutboundMessage.error("Mensaje JSON inválido"));
        } catch (IllegalArgumentException | IllegalStateException ex) {
            send(ctx, WsOutboundMessage.error(ex.getMessage()));
        } catch (RuntimeException ex) {
            LOGGER.warn("Error procesando evento WebSocket: {}", ex.getMessage());
            send(ctx, WsOutboundMessage.error("Error interno procesando la acción"));
        }
    }

    private void onClose(WsCloseContext ctx) {
        BoundSession bound = sessionIndex.remove(ctx.sessionId());
        if (bound == null) {
            return;
        }
        Map<String, WsContext> playersInRoom = roomSessions.get(bound.roomCode());
        if (playersInRoom != null && playersInRoom.remove(bound.playerId(), ctx)) {
            disconnectQuietly(bound.roomCode(), bound.playerId());
            broadcastRoomState(bound.roomCode());
        }
    }

    private void onError(WsErrorContext ctx) {
        LOGGER.debug("Error de socket en sessionId={}: {}",
                ctx.sessionId(), ctx.error() != null ? ctx.error().getMessage() : "desconocido");
    }

    private void dispatchMessage(WsMessageContext ctx, WsInboundMessage message) {
        if (message == null || message.type() == null || message.type().isBlank()) {
            send(ctx, WsOutboundMessage.error("El campo 'type' es obligatorio"));
            return;
        }
        String eventType = message.type().trim().toUpperCase(Locale.ROOT);
        BiConsumer<WsMessageContext, WsInboundMessage> handler = eventHandlers.get(eventType);
        if (handler == null) {
            send(ctx, WsOutboundMessage.error("Evento WebSocket no soportado: " + eventType));
            return;
        }
        handler.accept(ctx, message);
    }

    private void handleJoin(WsMessageContext ctx, WsInboundMessage msg) {
        String roomCode = normalizeRoomCode(ctx);
        SessionTicket ticket = hasText(msg.playerId())
                ? gameController.reconnectSession(roomCode, msg.sessionToken(), msg.playerId())
                : gameController.joinRoomSession(roomCode, msg.playerName());
        bindSession(ctx, roomCode, ticket.playerId());
        send(ctx, WsOutboundMessage.of("JOINED", ticket));
        broadcastRoomState(roomCode);
    }

    private void handleReconnect(WsMessageContext ctx, WsInboundMessage msg) {
        String roomCode = normalizeRoomCode(ctx);
        SessionTicket ticket = gameController.reconnectSession(roomCode, msg.sessionToken(), msg.playerId());
        bindSession(ctx, roomCode, ticket.playerId());
        send(ctx, WsOutboundMessage.of("RECONNECTED", ticket));
        broadcastRoomState(roomCode);
    }

    private void handleStartGame(WsMessageContext ctx, WsInboundMessage msg) {
        String roomCode = normalizeRoomCode(ctx);
        String playerId = resolvePlayerId(ctx, msg);
        gameController.startGame(roomCode, playerId);
        broadcastRoomState(roomCode);
    }

    private void handleDrawDeck(WsMessageContext ctx, WsInboundMessage msg) {
        String roomCode = normalizeRoomCode(ctx);
        String playerId = resolvePlayerId(ctx, msg);
        gameController.drawFromDeck(roomCode, playerId);
        broadcastRoomState(roomCode);
    }

    private void handleDrawDiscard(WsMessageContext ctx, WsInboundMessage msg) {
        String roomCode = normalizeRoomCode(ctx);
        String playerId = resolvePlayerId(ctx, msg);
        gameController.drawFromDiscard(roomCode, playerId);
        broadcastRoomState(roomCode);
    }

    private void handleDiscard(WsMessageContext ctx, WsInboundMessage msg) {
        String roomCode = normalizeRoomCode(ctx);
        String playerId = resolvePlayerId(ctx, msg);
        gameController.discard(roomCode, playerId, msg.cardId());
        broadcastRoomState(roomCode);
    }

    private void handleDeclareClose(WsMessageContext ctx, WsInboundMessage msg) {
        String roomCode = normalizeRoomCode(ctx);
        String playerId = resolvePlayerId(ctx, msg);
        GameMode closeMode = parseCloseMode(msg.closeMode());
        RoundSummary summary = gameController.declareClose(roomCode, playerId, msg.cardId(), closeMode);
        broadcastRawEvent(roomCode, WsOutboundMessage.of("ROUND_CLOSED", summary));
        broadcastRoomState(roomCode);
    }

    private static GameMode parseCloseMode(String rawCloseMode) {
        if (!hasText(rawCloseMode)) {
            return null;
        }
        String normalized = rawCloseMode.trim().toUpperCase(Locale.ROOT);
        if ("REGULAR".equals(normalized) || "CLASICA".equals(normalized)) {
            return GameMode.CLASSIC;
        }
        try {
            return GameMode.valueOf(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "Modalidad de cierre no válida: " + rawCloseMode + ". Usa CLASSIC (Regular) o APUNTADO");
        }
    }

    private void handleLeaveRoom(WsMessageContext ctx, WsInboundMessage msg) {
        String roomCode = normalizeRoomCode(ctx);
        String playerId = resolvePlayerId(ctx, msg);
        LeaveResult result = gameController.leaveRoom(roomCode, playerId);
        unbindSession(ctx, roomCode, playerId);
        send(ctx, WsOutboundMessage.of("LEFT_ROOM", result));
        notifyPlayerLeft(result);
    }

    public void notifyPlayerLeft(LeaveResult result) {
        Map<String, WsContext> players = roomSessions.get(result.roomCode());
        if (players != null) {
            players.remove(result.leftPlayerId());
        }
        broadcastRawEvent(result.roomCode(), WsOutboundMessage.of("PLAYER_LEFT", result));
        broadcastRoomState(result.roomCode());
    }

    private void unbindSession(WsContext ctx, String roomCode, String playerId) {
        sessionIndex.remove(ctx.sessionId());
        Map<String, WsContext> players = roomSessions.get(roomCode);
        if (players != null) {
            players.remove(playerId, ctx);
        }
    }

    private void handleSyncState(WsMessageContext ctx, WsInboundMessage msg) {
        String roomCode = normalizeRoomCode(ctx);
        String playerId = resolvePlayerId(ctx, msg);
        PlayerGameView view = gameController.getPlayerView(roomCode, playerId);
        send(ctx, WsOutboundMessage.of("GAME_STATE", view));
    }

    private void bindSession(WsContext ctx, String roomCode, String playerId) {
        roomSessions.computeIfAbsent(roomCode, key -> new ConcurrentHashMap<>()).put(playerId, ctx);
        sessionIndex.put(ctx.sessionId(), new BoundSession(roomCode, playerId));
    }

    private String resolvePlayerId(WsContext ctx, WsInboundMessage msg) {
        BoundSession bound = sessionIndex.get(ctx.sessionId());
        if (bound != null) {
            return bound.playerId();
        }
        if (hasText(msg.playerId())) {
            return msg.playerId().trim();
        }
        throw new IllegalStateException("Debe autenticarse en la sala con JOIN o RECONNECT primero");
    }

    private void broadcastRoomState(String roomCode) {
        Map<String, WsContext> players = roomSessions.get(roomCode);
        if (players == null || players.isEmpty()) {
            return;
        }
        for (Map.Entry<String, WsContext> entry : players.entrySet()) {
            WsContext client = entry.getValue();
            if (isOpen(client)) {
                PlayerGameView personalizedView = gameController.getPlayerView(roomCode, entry.getKey());
                send(client, WsOutboundMessage.of("GAME_STATE", personalizedView));
            }
        }
    }

    private void broadcastRawEvent(String roomCode, WsOutboundMessage message) {
        Map<String, WsContext> players = roomSessions.get(roomCode);
        if (players == null || players.isEmpty()) {
            return;
        }
        for (WsContext client : players.values()) {
            if (isOpen(client)) {
                send(client, message);
            }
        }
    }

    private void send(WsContext ctx, WsOutboundMessage outbound) {
        try {
            if (isOpen(ctx)) {
                ctx.send(objectMapper.writeValueAsString(outbound));
            }
        } catch (JsonProcessingException ex) {
            LOGGER.error("Error serializando mensaje WebSocket {}", outbound.type(), ex);
        }
    }

    private void disconnectQuietly(String roomCode, String playerId) {
        try {
            gameController.disconnect(roomCode, playerId);
        } catch (RuntimeException ex) {
            LOGGER.debug("No se pudo marcar desconexión para {} en {}: {}", playerId, roomCode, ex.getMessage());
        }
    }

    private static boolean isOpen(WsContext ctx) {
        return ctx != null && ctx.session != null && ctx.session.isOpen();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizeRoomCode(WsContext ctx) {
        return ctx.pathParam("roomCode").trim().toUpperCase(Locale.ROOT);
    }

    private record BoundSession(String roomCode, String playerId) {
    }
}

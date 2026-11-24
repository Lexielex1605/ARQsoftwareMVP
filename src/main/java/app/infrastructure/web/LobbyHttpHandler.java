package app.infrastructure.web;

import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.core.GameMode;
import app.gameplay.orchestrator.IGameController;
import app.gameplay.orchestrator.LeaveResult;
import app.gameplay.orchestrator.RoomOverview;
import app.gameplay.orchestrator.SessionTicket;
import app.infrastructure.web.dto.CreateRoomRequest;
import app.infrastructure.web.dto.JoinRoomRequest;
import app.infrastructure.web.dto.ReconnectRequest;
import app.infrastructure.web.dto.UpdateConfigRequest;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Adaptador HTTP REST para operaciones mínimas de Lobby (salud, opciones, crear/unirse/validar sala).
 * Se comunica únicamente con IGameController y tipos de app.core (RG-01).
 */
public final class LobbyHttpHandler {

    private final IGameController gameController;
    private final Consumer<LeaveResult> onPlayerLeft;

    public LobbyHttpHandler(IGameController gameController) {
        this(gameController, result -> { });
    }

    public LobbyHttpHandler(IGameController gameController, Consumer<LeaveResult> onPlayerLeft) {
        this.gameController = Objects.requireNonNull(gameController, "gameController no puede ser nulo");
        this.onPlayerLeft = Objects.requireNonNull(onPlayerLeft, "onPlayerLeft no puede ser nulo");
    }

    public void registerRoutes(Javalin app) {
        app.get("/api/health", this::health);
        app.get("/api/config/options", this::configOptions);
        app.post("/api/rooms", this::createRoom);
        app.get("/api/rooms/{roomCode}", this::getRoom);
        app.post("/api/rooms/{roomCode}/join", this::joinRoom);
        app.post("/api/rooms/{roomCode}/reconnect", this::reconnect);
        app.post("/api/rooms/{roomCode}/leave", this::leaveRoom);
        app.put("/api/rooms/{roomCode}/config", this::updateConfig);
    }

    private void health(Context ctx) {
        ctx.json(Map.of("status", "UP"));
    }

    private void configOptions(Context ctx) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("gameModes", mapGameModes());
        options.put("cardValuePolicies", mapCardValuePolicies());
        options.put("cyclePolicies", mapCyclePolicies());
        options.put("eliminationLimits", Arrays.stream(EliminationLimit.values())
                .map(EliminationLimit::getPoints)
                .toList());
        options.put("minPlayers", GameConfig.MIN_PLAYERS);
        options.put("maxPlayers", GameConfig.MAX_PLAYERS);
        options.put("playersPerDeck", GameConfig.PLAYERS_PER_DECK);
        options.put("minDecks", GameConfig.MIN_DECKS);
        options.put("maxDecks", GameConfig.MAX_DECKS);
        options.put("minApuntadoCloseLimit", GameConfig.MIN_APUNTADO_CLOSE_LIMIT);
        options.put("maxApuntadoCloseLimit", GameConfig.MAX_APUNTADO_CLOSE_LIMIT);
        options.put("defaultApuntadoCloseLimit", GameConfig.DEFAULT_APUNTADO_CLOSE_LIMIT);
        options.put("defaultConfig", new GameConfig());
        ctx.json(options);
    }

    private void createRoom(Context ctx) {
        CreateRoomRequest request = ctx.bodyAsClass(CreateRoomRequest.class);
        GameConfig config = request.toGameConfig();
        SessionTicket ticket = gameController.createRoomSession(request.hostName(), config);
        ctx.status(HttpStatus.CREATED).json(ticket);
    }

    private void getRoom(Context ctx) {
        String roomCode = ctx.pathParam("roomCode");
        RoomOverview overview = gameController.getRoomOverview(roomCode);
        ctx.json(overview);
    }

    private void joinRoom(Context ctx) {
        String roomCode = ctx.pathParam("roomCode");
        JoinRoomRequest request = ctx.bodyAsClass(JoinRoomRequest.class);
        SessionTicket ticket = gameController.joinRoomSession(roomCode, request.playerName());
        ctx.json(ticket);
    }

    private void reconnect(Context ctx) {
        String roomCode = ctx.pathParam("roomCode");
        ReconnectRequest request = ctx.bodyAsClass(ReconnectRequest.class);
        SessionTicket ticket = gameController.reconnectSession(
                roomCode, request.sessionToken(), request.playerId());
        ctx.json(ticket);
    }

    private void leaveRoom(Context ctx) {
        String roomCode = ctx.pathParam("roomCode");
        ReconnectRequest request = ctx.bodyAsClass(ReconnectRequest.class);
        LeaveResult result = gameController.leaveRoom(roomCode, request.playerId());
        onPlayerLeft.accept(result);
        ctx.json(result);
    }

    private void updateConfig(Context ctx) {
        String roomCode = ctx.pathParam("roomCode");
        UpdateConfigRequest request = ctx.bodyAsClass(UpdateConfigRequest.class);
        gameController.updateConfig(roomCode, request.requesterPlayerId(), request.toGameConfig());
        ctx.json(gameController.getRoomOverview(roomCode));
    }

    private List<Map<String, String>> mapGameModes() {
        return Arrays.stream(GameMode.values())
                .map(mode -> Map.of("id", mode.name(), "label", mode.getDisplayName()))
                .toList();
    }

    private List<Map<String, String>> mapCardValuePolicies() {
        return Arrays.stream(CardValuePolicy.values())
                .map(policy -> Map.of("id", policy.name(), "label", policy.getDescription()))
                .toList();
    }

    private List<Map<String, String>> mapCyclePolicies() {
        return Arrays.stream(CyclePolicy.values())
                .map(policy -> Map.of("id", policy.name(), "label", policy.getDescription()))
                .toList();
    }
}

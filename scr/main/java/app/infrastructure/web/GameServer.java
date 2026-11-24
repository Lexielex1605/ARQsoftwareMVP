package app.infrastructure.web;

import app.gameplay.orchestrator.IGameController;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import io.javalin.http.staticfiles.Location;
import io.javalin.json.JavalinJackson;

import java.util.Map;
import java.util.Objects;

/**
 * Servidor web Javalin que sirve archivos estáticos desde /static, expone los endpoints HTTP
 * mínimos del Lobby y habilita el canal en tiempo real WebSocket (/ws/{roomCode}).
 */
public final class GameServer {

    public static final int DEFAULT_PORT = 7070;

    private final Javalin javalin;

    public GameServer(IGameController gameController) {
        Objects.requireNonNull(gameController, "gameController no puede ser nulo");
        ObjectMapper objectMapper = createObjectMapper();
        this.javalin = createJavalinApp(objectMapper);

        registerExceptionHandlers(javalin);
        GameWebSocketHandler wsHandler = new GameWebSocketHandler(gameController, objectMapper);
        new LobbyHttpHandler(gameController, wsHandler::notifyPlayerLeft).registerRoutes(javalin);
        wsHandler.registerRoutes(javalin);
    }

    public GameServer start(int port) {
        javalin.start("0.0.0.0", port);
        return this;
    }

    public int port() {
        return javalin.port();
    }

    public void stop() {
        javalin.stop();
    }

    private static ObjectMapper createObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    private static Javalin createJavalinApp(ObjectMapper objectMapper) {
        return Javalin.create(config -> {
            config.staticFiles.add(staticFiles -> {
                staticFiles.hostedPath = "/";
                staticFiles.directory = "/static";
                staticFiles.location = Location.CLASSPATH;
                staticFiles.headers = Map.of("Cache-Control", "no-cache, no-store, must-revalidate");
            });
            config.jsonMapper(new JavalinJackson(objectMapper, false));
            config.showJavalinBanner = false;
        });
    }

    private static void registerExceptionHandlers(Javalin app) {
        app.exception(IllegalArgumentException.class, (ex, ctx) ->
                ctx.status(HttpStatus.BAD_REQUEST).json(Map.of("error", safeMessage(ex))));
        app.exception(IllegalStateException.class, (ex, ctx) ->
                ctx.status(HttpStatus.CONFLICT).json(Map.of("error", safeMessage(ex))));
        app.exception(Exception.class, (ex, ctx) ->
                ctx.status(HttpStatus.INTERNAL_SERVER_ERROR).json(Map.of("error", safeMessage(ex))));
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : "Error procesando la solicitud";
    }
}

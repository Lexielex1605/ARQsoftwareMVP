package unit.infrastructure;

import app.Main;
import app.infrastructure.web.GameServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebServerAndWebSocketTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private GameServer server;
    private String baseHttpUrl;
    private String baseWsUrl;

    @BeforeEach
    void setUp() {
        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("web_integration.db");
        server = Main.buildServer(jdbcUrl).start(0);
        baseHttpUrl = "http://localhost:" + server.port();
        baseWsUrl = "ws://localhost:" + server.port();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void servesStaticFrontendAndCardAssets() throws Exception {
        HttpResponse<String> index = getText("/");
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("hand-board"));

        assertEquals(200, getText("/css/styles.css").statusCode());
        assertEquals(200, getText("/js/board.js").statusCode());
        assertEquals(200, getText("/js/app.js").statusCode());

        assertEquals(200, getBytes("/assets/cards/cardback/cardback_red.png").statusCode());
        assertEquals(200, getBytes("/assets/cards/cardback/cardback_blue.png").statusCode());
        assertEquals(200, getBytes("/assets/cards/spades/card_spades_A.png").statusCode());
        assertEquals(200, getBytes("/assets/cards/jokers/card_joker_red.png").statusCode());
    }

    @Test
    void exposesLobbyHttpEndpointsAndValidatesGameConfig() throws Exception {
        HttpResponse<String> health = getText("/api/health");
        assertEquals(200, health.statusCode());
        assertEquals("UP", mapper.readTree(health.body()).get("status").asText());

        HttpResponse<String> options = getText("/api/config/options");
        assertEquals(200, options.statusCode());
        JsonNode optionsJson = mapper.readTree(options.body());
        assertEquals(5, optionsJson.get("eliminationLimits").size());

        // Crear sala válida
        HttpResponse<String> createRes = postJson("/api/rooms", """
                {
                  "hostName": "Ana",
                  "gameMode": "CLASSIC",
                  "maxPlayers": 4,
                  "deckCount": 2,
                  "includeJokers": true,
                  "cardValuePolicy": "STANDARD",
                  "cyclePolicy": "CYCLIC",
                  "eliminationLimit": 101
                }
                """);
        assertEquals(201, createRes.statusCode());
        JsonNode hostTicket = mapper.readTree(createRes.body());
        String roomCode = hostTicket.get("roomCode").asText();
        assertEquals(6, roomCode.length());
        assertTrue(hostTicket.get("host").asBoolean());

        // Consultar resumen de sala
        HttpResponse<String> roomRes = getText("/api/rooms/" + roomCode);
        assertEquals(200, roomRes.statusCode());
        assertEquals("ESPERANDO", mapper.readTree(roomRes.body()).get("status").asText());
        assertEquals(1, mapper.readTree(roomRes.body()).get("playerCount").asInt());

        // Unirse con nombre duplicado -> HTTP 400
        HttpResponse<String> dupJoin = postJson("/api/rooms/" + roomCode + "/join", "{\"playerName\":\"ana\"}");
        assertEquals(400, dupJoin.statusCode());
        assertTrue(mapper.readTree(dupJoin.body()).get("error").asText().contains("Ya existe un jugador"));

        // Crear sala con 16 jugadores (4 barajas) y límite de cierre Apuntado configurable (< 9)
        HttpResponse<String> bigRoomRes = postJson("/api/rooms", """
                {
                  "hostName": "Host16",
                  "gameMode": "APUNTADO",
                  "maxPlayers": 16,
                  "deckCount": 4,
                  "apuntadoCloseLimit": 9
                }
                """);
        assertEquals(201, bigRoomRes.statusCode());
        JsonNode bigConfig = mapper.readTree(bigRoomRes.body()).get("config");
        assertEquals(16, bigConfig.get("maxPlayers").asInt());
        assertEquals(9, bigConfig.get("apuntadoCloseLimit").asInt());

        // Configuración inválida (6 jugadores con 1 baraja) -> HTTP 400
        HttpResponse<String> badRoom = postJson("/api/rooms", """
                {
                  "hostName": "Error",
                  "maxPlayers": 6,
                  "deckCount": 1
                }
                """);
        assertEquals(400, badRoom.statusCode());
        assertNotNull(mapper.readTree(badRoom.body()).get("error"));
    }

    @Test
    void supportsMixedModeCreationAndCloseModeValidationOverHttpAndWebSocket() throws Exception {
        JsonNode optionsJson = mapper.readTree(getText("/api/config/options").body());
        boolean hasMixed = false;
        for (JsonNode mode : optionsJson.get("gameModes")) {
            if ("MIXED".equals(mode.get("id").asText())) {
                hasMixed = true;
            }
        }
        assertTrue(hasMixed, "/api/config/options debe exponer la modalidad MIXED");

        HttpResponse<String> createRes = postJson("/api/rooms", """
                {
                  "hostName": "Mateo",
                  "gameMode": "MIXED",
                  "maxPlayers": 4,
                  "deckCount": 2,
                  "includeJokers": true
                }
                """);
        assertEquals(201, createRes.statusCode());
        JsonNode p1 = mapper.readTree(createRes.body());
        String roomCode = p1.get("roomCode").asText();
        assertEquals("MIXED", p1.get("config").get("gameMode").asText());

        JsonNode p2 = mapper.readTree(postJson("/api/rooms/" + roomCode + "/join",
                "{\"playerName\":\"Nicole\"}").body());

        TestWsClient ws1 = connectWs(roomCode);
        TestWsClient ws2 = connectWs(roomCode);
        try {
            ws1.send(String.format("{\"type\":\"JOIN\",\"playerId\":\"%s\",\"sessionToken\":\"%s\"}",
                    p1.get("playerId").asText(), p1.get("sessionToken").asText()));
            ws1.awaitEvent("GAME_STATE");
            ws2.send(String.format("{\"type\":\"JOIN\",\"playerId\":\"%s\",\"sessionToken\":\"%s\"}",
                    p2.get("playerId").asText(), p2.get("sessionToken").asText()));
            ws2.awaitEvent("GAME_STATE");

            ws1.send("{\"type\":\"START_GAME\"}");
            JsonNode hostState = ws1.awaitGameStateWithStatus("EN_CURSO");
            JsonNode guestState = ws2.awaitGameStateWithStatus("EN_CURSO");
            assertEquals("MIXED", hostState.get("config").get("gameMode").asText());
            assertEquals("MIXED", guestState.get("config").get("gameMode").asText());

            // En modo MIXED, declarar cierre sin closeMode devuelve ERROR claro
            ws1.send("{\"type\":\"DECLARE_CLOSE\"}");
            JsonNode missingModeErr = ws1.awaitEvent("ERROR");
            assertTrue(missingModeErr.get("error").asText().contains("Mixto"));

            // Enviar alias REGULAR es aceptado por el transporte y validado por reglas Clásicas
            ws1.send("{\"type\":\"DECLARE_CLOSE\",\"closeMode\":\"REGULAR\"}");
            JsonNode ruleErr = ws1.awaitEvent("ERROR");
            assertTrue(ruleErr.get("error").asText().contains("Clásica"));

            // Avanzar turno al jugador no anfitrión (P2) y verificar que también puede enviar closeMode
            ws1.send("{\"type\":\"DRAW_DECK\"}");
            JsonNode p1Drawn = ws1.awaitGameStateWithPhase("MUST_DISCARD");
            String cardId = p1Drawn.get("viewerHand").get(0).get("id").asText();
            ws1.send(String.format("{\"type\":\"DISCARD\",\"cardId\":\"%s\"}", cardId));
            ws2.awaitGameStateWithTurn(p2.get("playerId").asText());

            ws2.send("{\"type\":\"DECLARE_CLOSE\",\"closeMode\":\"APUNTADO\"}");
            JsonNode guestRuleErr = ws2.awaitEvent("ERROR");
            assertTrue(guestRuleErr.get("error").asText().contains("Apuntado"));
        } finally {
            ws1.close();
            ws2.close();
        }
    }

    @Test
    void broadcastsPlayerLeftNotificationAndDeclaresVictoryByAbandonmentOverWebSocket() throws Exception {
        JsonNode p1 = mapper.readTree(postJson("/api/rooms",
                "{\"hostName\":\"Mateo\",\"gameMode\":\"CLASSIC\",\"maxPlayers\":4,\"deckCount\":2}").body());
        String roomCode = p1.get("roomCode").asText();
        String p1Id = p1.get("playerId").asText();
        String p1Token = p1.get("sessionToken").asText();

        JsonNode p2 = mapper.readTree(postJson("/api/rooms/" + roomCode + "/join",
                "{\"playerName\":\"Nicole\"}").body());
        String p2Id = p2.get("playerId").asText();
        String p2Token = p2.get("sessionToken").asText();

        TestWsClient ws1 = connectWs(roomCode);
        TestWsClient ws2 = connectWs(roomCode);
        try {
            ws1.send(String.format("{\"type\":\"JOIN\",\"playerId\":\"%s\",\"sessionToken\":\"%s\"}", p1Id, p1Token));
            ws1.awaitEvent("GAME_STATE");
            ws2.send(String.format("{\"type\":\"JOIN\",\"playerId\":\"%s\",\"sessionToken\":\"%s\"}", p2Id, p2Token));
            ws2.awaitEvent("GAME_STATE");

            ws1.send("{\"type\":\"START_GAME\"}");
            ws1.awaitGameStateWithStatus("EN_CURSO");
            ws2.awaitGameStateWithStatus("EN_CURSO");

            // Nicole abandona explícitamente
            ws2.send("{\"type\":\"LEAVE_ROOM\"}");
            assertNotNull(ws2.awaitEvent("LEFT_ROOM"));

            // Mateo recibe notificación PLAYER_LEFT con victoria por abandono y GAME_STATE FINALIZADA
            JsonNode leftNotif = ws1.awaitEvent("PLAYER_LEFT").get("payload");
            assertTrue(leftNotif.get("matchFinished").asBoolean());
            assertEquals(p1Id, leftNotif.get("matchWinnerId").asText());
            assertEquals("VICTORIA_POR_ABANDONO", leftNotif.get("victorySummary").get("closeType").asText());

            JsonNode finalState = ws1.awaitGameStateWithStatus("FINALIZADA");
            assertEquals("ABANDONADO", finalState.get("players").get(1).get("status").asText());

            // Nicole ya no puede reconectarse vía HTTP (409 Conflict)
            HttpResponse<String> rejoinRes = postJson("/api/rooms/" + roomCode + "/reconnect",
                    String.format("{\"sessionToken\":\"%s\",\"playerId\":\"%s\"}", p2Token, p2Id));
            assertEquals(409, rejoinRes.statusCode());
        } finally {
            ws1.close();
            ws2.close();
        }
    }

    @Test
    void executesRealTimeWebSocketFlowWithPerPlayerFilteringAndReconnect() throws Exception {
        JsonNode p1 = mapper.readTree(postJson("/api/rooms", """
                {"hostName":"Ana","gameMode":"CLASSIC","maxPlayers":4,"deckCount":2,"includeJokers":true}
                """).body());
        String roomCode = p1.get("roomCode").asText();
        String p1Id = p1.get("playerId").asText();
        String p1Token = p1.get("sessionToken").asText();

        JsonNode p2 = mapper.readTree(postJson("/api/rooms/" + roomCode + "/join",
                """
                {"playerName":"Carlos"}
                """).body());
        String p2Id = p2.get("playerId").asText();
        String p2Token = p2.get("sessionToken").asText();

        TestWsClient ws1 = connectWs(roomCode);
        TestWsClient ws2 = connectWs(roomCode);

        try {
            // 1. JOIN ambos jugadores
            ws1.send(String.format("{\"type\":\"JOIN\",\"playerId\":\"%s\",\"sessionToken\":\"%s\"}", p1Id, p1Token));
            assertNotNull(ws1.awaitEvent("JOINED"));
            assertNotNull(ws1.awaitEvent("GAME_STATE"));

            ws2.send(String.format("{\"type\":\"JOIN\",\"playerId\":\"%s\",\"sessionToken\":\"%s\"}", p2Id, p2Token));
            assertNotNull(ws2.awaitEvent("JOINED"));
            ws1.awaitEvent("GAME_STATE");
            ws2.awaitEvent("GAME_STATE");

            // 2. START_GAME por el anfitrión -> ambos reciben GAME_STATE filtrado
            ws1.send("{\"type\":\"START_GAME\"}");
            JsonNode state1 = ws1.awaitGameStateWithStatus("EN_CURSO");
            JsonNode state2 = ws2.awaitGameStateWithStatus("EN_CURSO");

            assertEquals(10, state1.get("viewerHand").size());
            assertEquals(10, state2.get("viewerHand").size());
            assertEquals(p1Id, state1.get("currentTurnPlayerId").asText());
            assertEquals("MUST_DRAW", state1.get("turnPhase").asText());

            // Verificar que cada jugador recibe sus propias cartas distintas y rutas PNG exactas
            Set<String> hand1Ids = new HashSet<>();
            state1.get("viewerHand").forEach(card -> {
                hand1Ids.add(card.get("id").asText());
                assertTrue(card.get("imagePath").asText().startsWith("/assets/cards/"));
                assertTrue(card.get("imagePath").asText().endsWith(".png"));
            });
            state2.get("viewerHand").forEach(card -> assertTrue(!hand1Ids.contains(card.get("id").asText())));

            // 3. DRAW_DECK por P1 -> 11 cartas y MUST_DISCARD
            ws1.send("{\"type\":\"DRAW_DECK\"}");
            JsonNode afterDraw1 = ws1.awaitGameStateWithPhase("MUST_DISCARD");
            JsonNode afterDraw2 = ws2.awaitGameStateWithPhase("MUST_DISCARD");
            assertEquals(11, afterDraw1.get("viewerHand").size());
            assertEquals(11, afterDraw2.get("players").get(0).get("cardCount").asInt());

            // 4. DISCARD por P1 -> vuelve a 10 cartas y turno avanza a P2
            String discardCardId = afterDraw1.get("viewerHand").get(0).get("id").asText();
            ws1.send(String.format("{\"type\":\"DISCARD\",\"cardId\":\"%s\"}", discardCardId));
            JsonNode afterDiscard1 = ws1.awaitGameStateWithTurn(p2Id);
            JsonNode afterDiscard2 = ws2.awaitGameStateWithTurn(p2Id);
            assertEquals(10, afterDiscard1.get("viewerHand").size());
            assertEquals(discardCardId, afterDiscard2.get("topDiscardCard").get("id").asText());

            // 5. Evento inválido -> ERROR sin cerrar conexión
            ws2.send("{\"type\":\"EVENTO_INEXISTENTE\"}");
            JsonNode errMsg = ws2.awaitEvent("ERROR");
            assertNotNull(errMsg.get("error"));

            // 6. Reconexión de P2 tras cerrar su socket
            ws2.close();
            TestWsClient ws2Reconnected = connectWs(roomCode);
            try {
                ws2Reconnected.send(String.format(
                        "{\"type\":\"RECONNECT\",\"playerId\":\"%s\",\"sessionToken\":\"%s\"}", p2Id, p2Token));
                assertNotNull(ws2Reconnected.awaitEvent("RECONNECTED"));
                JsonNode restoredState = ws2Reconnected.awaitGameStateWithStatus("EN_CURSO");
                assertEquals(10, restoredState.get("viewerHand").size());
                assertNotEquals("", restoredState.get("viewerHand").get(0).get("id").asText());
            } finally {
                ws2Reconnected.close();
            }
        } finally {
            ws1.close();
            ws2.close();
        }
    }

    private HttpResponse<String> getText(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseHttpUrl + path)).GET().build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseHttpUrl + path)).GET().build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<String> postJson(String path, String jsonBody) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseHttpUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private TestWsClient connectWs(String roomCode) throws Exception {
        TestWsClient client = new TestWsClient(mapper);
        WebSocket ws = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(baseWsUrl + "/ws/" + roomCode), client)
                .get(5, TimeUnit.SECONDS);
        client.attach(ws);
        return client;
    }

    private static final class TestWsClient implements WebSocket.Listener {
        private final ObjectMapper mapper;
        private final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;

        TestWsClient(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        void attach(WebSocket socket) {
            this.socket = socket;
        }

        void send(String text) {
            socket.sendText(text, true).join();
        }

        void close() {
            if (socket != null && !socket.isOutputClosed()) {
                try {
                    socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(2, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                    socket.abort();
                }
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String full = partial.toString();
                partial.setLength(0);
                try {
                    inbox.offer(mapper.readTree(full));
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        JsonNode awaitEvent(String expectedType) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode msg = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (msg != null && expectedType.equals(msg.get("type").asText())) {
                    return msg;
                }
            }
            throw new AssertionError("Timeout esperando evento WebSocket: " + expectedType);
        }

        JsonNode awaitGameStateWithStatus(String expectedStatus) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode msg = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (msg != null && "GAME_STATE".equals(msg.get("type").asText())
                        && expectedStatus.equals(msg.get("payload").get("matchStatus").asText())) {
                    return msg.get("payload");
                }
            }
            throw new AssertionError("Timeout esperando GAME_STATE con estado: " + expectedStatus);
        }

        JsonNode awaitGameStateWithPhase(String expectedPhase) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode msg = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (msg != null && "GAME_STATE".equals(msg.get("type").asText())
                        && expectedPhase.equals(msg.get("payload").get("turnPhase").asText())) {
                    return msg.get("payload");
                }
            }
            throw new AssertionError("Timeout esperando GAME_STATE con fase: " + expectedPhase);
        }

        JsonNode awaitGameStateWithTurn(String expectedPlayerId) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode msg = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (msg != null && "GAME_STATE".equals(msg.get("type").asText())
                        && expectedPlayerId.equals(msg.get("payload").get("currentTurnPlayerId").asText())) {
                    return msg.get("payload");
                }
            }
            throw new AssertionError("Timeout esperando GAME_STATE con turno de: " + expectedPlayerId);
        }
    }
}

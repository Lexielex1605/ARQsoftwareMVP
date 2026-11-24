package unit.infrastructure;

import app.core.Card;
import app.core.CardBack;
import app.core.GameConfig;
import app.core.Rank;
import app.core.Suit;
import app.core.infrastructure.SqliteDatabaseManager;
import app.deck.service.IDeckService;
import app.gameplay.orchestrator.GameOrchestrator;
import app.gameplay.orchestrator.IGameController;
import app.infrastructure.web.GameServer;
import app.player.infrastructure.SqlitePlayerRepository;
import app.player.service.IPlayerService;
import app.player.service.PlayerSessionManager;
import app.rules.apuntado.ApuntadoRulesStrategy;
import app.rules.classic.ClassicRulesStrategy;
import app.rules.engine.IRuleEngine;
import app.rules.engine.RuleEngine;
import app.scoring.infrastructure.SqliteScoreRepository;
import app.scoring.service.IScoreRepository;
import app.scoring.service.ScoreManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prueba end-to-end sobre servidor Javalin + WebSockets reales verificando:
 * - Prohibición de descartar la misma carta tomada del pozo vía WS
 * - Prohibición de cerrar después de robar vía WS
 * - Cierre Apuntado del jugador NO anfitrión en modo Mixto sumando su excedente (+6 pts)
 * - Cierre Regular en Ronda 2 y fin de partida propagados en ROUND_CLOSED y GAME_STATE
 */
public class WebSocketGameplayEndToEndTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private GameServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    private static Card card(Suit suit, Rank rank, int deck) {
        return new Card("D" + deck + "-" + suit + "-" + rank, suit, rank, CardBack.forDeckNumber(deck), deck);
    }

    @Test
    void validatesTurnRulesMixedClosesAndScoreBroadcastEndToEndOverWebSockets() throws Exception {
        // Ronda 1: P1 tiene 15 pts sobrantes; P2 (no anfitrión) tiene cierre Apuntado 3-3-3 + 6♣ (+6 pts, limite < 8)
        List<Card> r1P1 = List.of(
                card(Suit.DIAMONDS, Rank.TEN, 1),
                card(Suit.DIAMONDS, Rank.JACK, 1),
                card(Suit.DIAMONDS, Rank.QUEEN, 1),
                card(Suit.SPADES, Rank.NINE, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.THREE, 2),
                card(Suit.DIAMONDS, Rank.FOUR, 2),
                card(Suit.HEARTS, Rank.TWO, 2),
                card(Suit.SPADES, Rank.SIX, 2)
        );
        List<Card> r1P2Apuntado = List.of(
                card(Suit.SPADES, Rank.TWO, 1),
                card(Suit.SPADES, Rank.THREE, 1),
                card(Suit.SPADES, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.EIGHT, 1),
                card(Suit.DIAMONDS, Rank.EIGHT, 1),
                card(Suit.CLUBS, Rank.EIGHT, 1),
                card(Suit.HEARTS, Rank.JACK, 1),
                card(Suit.HEARTS, Rank.QUEEN, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.SIX, 1) // sobrante = 6 pts
        );
        List<Card> round1 = new ArrayList<>();
        round1.addAll(r1P1);
        round1.addAll(r1P2Apuntado);
        round1.add(card(Suit.CLUBS, Rank.ACE, 2)); // carta inicial en el pozo

        // Ronda 2 (inicia P2; P2 descarta y pasa turno a P1; P1 cierra Regular 7+3 = -20 pts; P2 suma 64 -> 70 >= 51 -> ELIMINADO!)
        List<Card> r2P1SevenThree = List.of(
                card(Suit.HEARTS, Rank.THREE, 1),
                card(Suit.HEARTS, Rank.FOUR, 1),
                card(Suit.HEARTS, Rank.FIVE, 1),
                card(Suit.HEARTS, Rank.SIX, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.EIGHT, 1),
                card(Suit.HEARTS, Rank.NINE, 1),
                card(Suit.SPADES, Rank.KING, 1),
                card(Suit.DIAMONDS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.KING, 1)
        );
        List<Card> r2P2HighDeadwood = List.of(
                card(Suit.SPADES, Rank.SIX, 2),
                card(Suit.SPADES, Rank.NINE, 2),
                card(Suit.SPADES, Rank.QUEEN, 2),
                card(Suit.HEARTS, Rank.FOUR, 2),
                card(Suit.HEARTS, Rank.EIGHT, 2),
                card(Suit.HEARTS, Rank.JACK, 2),
                card(Suit.DIAMONDS, Rank.TWO, 2),
                card(Suit.DIAMONDS, Rank.SEVEN, 2),
                card(Suit.CLUBS, Rank.FIVE, 2),
                card(Suit.CLUBS, Rank.THREE, 2)
        );
        List<Card> round2 = new ArrayList<>();
        round2.addAll(r2P1SevenThree);
        round2.addAll(r2P2HighDeadwood);
        round2.add(card(Suit.CLUBS, Rank.ACE, 1));
        round2.add(card(Suit.DIAMONDS, Rank.ACE, 1));

        String jdbcUrl = "jdbc:sqlite:" + tempDir.resolve("ws_gameplay_e2e.db");
        SqliteDatabaseManager db = new SqliteDatabaseManager(jdbcUrl);
        db.initializeSchema();
        IPlayerService playerService = new PlayerSessionManager(new SqlitePlayerRepository(db));
        IScoreRepository scoreRepository = new SqliteScoreRepository(db);
        IRuleEngine ruleEngine = new RuleEngine(List.of(new ClassicRulesStrategy(), new ApuntadoRulesStrategy()));
        IGameController controller = new GameOrchestrator(
                () -> new ScriptedWsDeck(List.of(round1, round2)),
                ruleEngine,
                () -> new ScoreManager(scoreRepository),
                playerService
        );
        server = new GameServer(controller).start(0);
        String baseHttp = "http://localhost:" + server.port();
        String baseWs = "ws://localhost:" + server.port();

        JsonNode p1 = mapper.readTree(postJson(baseHttp + "/api/rooms", """
                {
                  "hostName": "Mateo",
                  "gameMode": "MIXED",
                  "maxPlayers": 4,
                  "deckCount": 2,
                  "eliminationLimit": 51,
                  "apuntadoCloseLimit": 8
                }
                """).body());
        String roomCode = p1.get("roomCode").asText();
        String p1Id = p1.get("playerId").asText();
        String p1Token = p1.get("sessionToken").asText();

        JsonNode p2 = mapper.readTree(postJson(baseHttp + "/api/rooms/" + roomCode + "/join",
                "{\"playerName\":\"Nicole\"}").body());
        String p2Id = p2.get("playerId").asText();
        String p2Token = p2.get("sessionToken").asText();

        WsClient ws1 = connectWs(baseWs, roomCode);
        WsClient ws2 = connectWs(baseWs, roomCode);
        try {
            ws1.send(String.format("{\"type\":\"JOIN\",\"playerId\":\"%s\",\"sessionToken\":\"%s\"}", p1Id, p1Token));
            ws1.awaitType("GAME_STATE");
            ws2.send(String.format("{\"type\":\"JOIN\",\"playerId\":\"%s\",\"sessionToken\":\"%s\"}", p2Id, p2Token));
            ws2.awaitType("GAME_STATE");

            ws1.send("{\"type\":\"START_GAME\"}");
            JsonNode startP1 = ws1.awaitGameState(s -> "EN_CURSO".equals(s.get("matchStatus").asText()));
            ws2.awaitGameState(s -> "EN_CURSO".equals(s.get("matchStatus").asText()));

            // 1. P1 roba del POZO -> intenta descartar esa misma carta -> ERROR
            String pozoCardId = startP1.get("topDiscardCard").get("id").asText();
            ws1.send("{\"type\":\"DRAW_DISCARD\"}");
            JsonNode afterPozoDraw = ws1.awaitGameState(s -> "MUST_DISCARD".equals(s.get("turnPhase").asText()));
            assertEquals(pozoCardId, afterPozoDraw.get("forbiddenDiscardCardId").asText());

            ws1.send(String.format("{\"type\":\"DISCARD\",\"cardId\":\"%s\"}", pozoCardId));
            assertTrue(ws1.awaitType("ERROR").get("error").asText().contains("pozo"));

            // 2. P1 intenta declarar cierre tras haber robado -> ERROR
            ws1.send("{\"type\":\"DECLARE_CLOSE\",\"closeMode\":\"APUNTADO\"}");
            assertTrue(ws1.awaitType("ERROR").get("error").asText().contains("robado"));

            // 3. P1 descarta otra carta válida -> turno pasa a P2 (Nicole, no anfitrión)
            String validDiscard = afterPozoDraw.get("viewerHand").get(0).get("id").asText();
            ws1.send(String.format("{\"type\":\"DISCARD\",\"cardId\":\"%s\"}", validDiscard));
            ws2.awaitGameState(s -> p2Id.equals(s.get("currentTurnPlayerId").asText()));

            // 4. Nicole (no anfitrión) declara cierre en modo Mixto eligiendo APUNTADO (+6 pts de la sobrante 6♣!)
            ws2.send("{\"type\":\"DECLARE_CLOSE\",\"closeMode\":\"APUNTADO\"}");
            JsonNode r1Closed = ws1.awaitType("ROUND_CLOSED").get("payload");
            assertEquals("APUNTADO", r1Closed.get("closeType").asText());
            assertEquals(6, r1Closed.get("closeBonus").asInt());

            JsonNode r2StateP2 = ws2.awaitGameState(s -> s.get("roundNumber").asInt() == 2);
            assertEquals(6, r2StateP2.get("players").get(1).get("accumulatedScore").asInt());

            // 5. Ronda 2: Nicole roba del mazo y descarta esa misma -> turno pasa a Mateo -> Mateo cierra Regular (-20) y gana!
            ws2.send("{\"type\":\"DRAW_DECK\"}");
            JsonNode nR2Drawn = ws2.awaitGameState(s -> "MUST_DISCARD".equals(s.get("turnPhase").asText()));
            ws2.send(String.format("{\"type\":\"DISCARD\",\"cardId\":\"%s\"}",
                    nR2Drawn.get("viewerHand").get(10).get("id").asText()));
            ws1.awaitGameState(s -> p1Id.equals(s.get("currentTurnPlayerId").asText())
                    && s.get("roundNumber").asInt() == 2);

            ws1.send("{\"type\":\"DECLARE_CLOSE\",\"closeMode\":\"REGULAR\"}");
            JsonNode r2Closed = ws2.awaitType("ROUND_CLOSED").get("payload");
            assertEquals("SEVEN_THREE", r2Closed.get("closeType").asText());
            assertEquals(-20, r2Closed.get("closeBonus").asInt());
            assertTrue(r2Closed.get("matchFinished").asBoolean());
            assertEquals(p1Id, r2Closed.get("matchWinnerId").asText());

            JsonNode finalState = ws1.awaitGameState(s -> "FINALIZADA".equals(s.get("matchStatus").asText()));
            assertEquals(p1Id, finalState.get("winnerPlayerId").asText());
            assertEquals("ELIMINADO", finalState.get("players").get(1).get("status").asText());
        } finally {
            ws1.close();
            ws2.close();
        }
    }

    private HttpResponse<String> postJson(String url, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private WsClient connectWs(String baseWs, String roomCode) throws Exception {
        WsClient client = new WsClient(mapper);
        WebSocket socket = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create(baseWs + "/ws/" + roomCode), client)
                .get(5, TimeUnit.SECONDS);
        client.attach(socket);
        return client;
    }

    private static final class WsClient implements WebSocket.Listener {
        private final ObjectMapper mapper;
        private final BlockingQueue<JsonNode> inbox = new LinkedBlockingQueue<>();
        private final StringBuilder partial = new StringBuilder();
        private WebSocket socket;

        WsClient(ObjectMapper mapper) { this.mapper = mapper; }
        void attach(WebSocket socket) { this.socket = socket; }
        void send(String json) { socket.sendText(json, true).join(); }

        void close() {
            if (socket != null && !socket.isOutputClosed()) {
                try { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(2, TimeUnit.SECONDS); }
                catch (Exception ignored) { socket.abort(); }
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                try { inbox.offer(mapper.readTree(text)); }
                catch (Exception ex) { throw new RuntimeException(ex); }
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        JsonNode awaitType(String type) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode msg = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (msg != null && type.equals(msg.get("type").asText())) {
                    return msg;
                }
            }
            throw new AssertionError("Timeout esperando evento WS: " + type);
        }

        JsonNode awaitGameState(java.util.function.Predicate<JsonNode> predicate) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (System.nanoTime() < deadline) {
                JsonNode msg = inbox.poll(200, TimeUnit.MILLISECONDS);
                if (msg != null && "GAME_STATE".equals(msg.get("type").asText())
                        && predicate.test(msg.get("payload"))) {
                    return msg.get("payload");
                }
            }
            throw new AssertionError("Timeout esperando GAME_STATE que cumpla condición");
        }
    }

    private static final class ScriptedWsDeck implements IDeckService {
        private final List<List<Card>> rounds;
        private int index = -1;
        private final Deque<Card> drawQueue = new ArrayDeque<>();
        private final Deque<Card> discardStack = new ArrayDeque<>();

        ScriptedWsDeck(List<List<Card>> rounds) { this.rounds = rounds; }

        @Override public void initialize(GameConfig config) { loadNext(); }
        @Override public void initialize(int deckCount, boolean includeJokers) { loadNext(); }

        private void loadNext() {
            index = Math.min(index + 1, rounds.size() - 1);
            drawQueue.clear();
            discardStack.clear();
            drawQueue.addAll(rounds.get(index));
        }

        @Override public void shuffle() { }
        @Override public Card drawFromDeck() { return drawQueue.removeFirst(); }
        @Override public Card drawFromDiscard() { return discardStack.pop(); }
        @Override public void discard(Card card) { discardStack.push(card); }
        @Override public Optional<Card> peekDiscard() { return Optional.ofNullable(discardStack.peek()); }
        @Override public Optional<CardBack> peekDeckBack() { return Optional.ofNullable(drawQueue.peek()).map(Card::back); }

        @Override
        public List<Card> dealHand(int cardCount) {
            List<Card> hand = new ArrayList<>(cardCount);
            for (int i = 0; i < cardCount; i++) {
                hand.add(drawFromDeck());
            }
            return hand;
        }

        @Override public boolean recycleDiscardPile() { return false; }
        @Override public int remainingDeckSize() { return drawQueue.size(); }
        @Override public int discardPileSize() { return discardStack.size(); }
    }
}

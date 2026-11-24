package app;

import app.core.infrastructure.SqliteDatabaseManager;
import app.deck.service.DeckManager;
import app.gameplay.orchestrator.GameOrchestrator;
import app.gameplay.orchestrator.IGameController;
import app.infrastructure.web.GameServer;
import app.player.infrastructure.SqlitePlayerRepository;
import app.player.service.IPlayerRepository;
import app.player.service.IPlayerService;
import app.player.service.PlayerSessionManager;
import app.rules.apuntado.ApuntadoRulesStrategy;
import app.rules.classic.ClassicRulesStrategy;
import app.rules.engine.IRuleEngine;
import app.rules.engine.RuleEngine;
import app.scoring.infrastructure.SqliteScoreRepository;
import app.scoring.service.IScoreRepository;
import app.scoring.service.ScoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Compositor Principal (Composition Root) del Monolito Modular.
 * Instancia SQLite, repositorios, servicios, motor de reglas, mediador y servidor Javalin.
 */
public final class Main {

    private static final Logger LOGGER = LoggerFactory.getLogger(Main.class);
    private static final String DEFAULT_DB_URL = "jdbc:sqlite:juego-cartas.db";

    private Main() {
    }

    public static void main(String[] args) {
        int port = resolvePort();
        String dbUrl = resolveDatabaseUrl();

        GameServer server = buildServer(dbUrl).start(port);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "javalin-shutdown"));
        LOGGER.info("Servidor del Juego de Cartas iniciado en http://localhost:{} (SQLite: {})",
                server.port(), dbUrl);
        logLanAddresses(server.port());
    }

    /**
     * Ensambla todas las dependencias del sistema a partir de la URL JDBC de SQLite.
     */
    public static GameServer buildServer(String jdbcUrl) {
        SqliteDatabaseManager database = new SqliteDatabaseManager(jdbcUrl);
        database.initializeSchema();

        IPlayerRepository playerRepository = new SqlitePlayerRepository(database);
        IPlayerService playerService = new PlayerSessionManager(playerRepository);

        IScoreRepository scoreRepository = new SqliteScoreRepository(database);
        IRuleEngine ruleEngine = new RuleEngine(List.of(
                new ClassicRulesStrategy(),
                new ApuntadoRulesStrategy()
        ));

        IGameController controller = new GameOrchestrator(
                DeckManager::new,
                ruleEngine,
                () -> new ScoreManager(scoreRepository),
                playerService
        );
        return new GameServer(controller);
    }

    private static void logLanAddresses(int port) {
        List<String> lanIps = discoverLanIpv4Addresses();
        if (lanIps.isEmpty()) {
            LOGGER.info("No se detectaron interfaces LAN IPv4 adicionales.");
            return;
        }
        for (String ip : lanIps) {
            LOGGER.info("Acceso LAN (otros dispositivos en la misma red Wi-Fi/Ethernet): http://{}:{}", ip, port);
        }
    }

    private static List<String> discoverLanIpv4Addresses() {
        List<String> ips = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface netIf = interfaces.nextElement();
                if (!netIf.isUp() || netIf.isLoopback() || netIf.isVirtual()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = netIf.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && addr.isSiteLocalAddress()) {
                        ips.add(addr.getHostAddress());
                    }
                }
            }
        } catch (SocketException ex) {
            LOGGER.debug("No se pudieron consultar interfaces de red LAN: {}", ex.getMessage());
        }
        return ips;
    }

    private static int resolvePort() {
        String configured = System.getProperty("app.port", System.getenv("PORT"));
        if (configured == null || configured.isBlank()) {
            return GameServer.DEFAULT_PORT;
        }
        return Integer.parseInt(configured.trim());
    }

    private static String resolveDatabaseUrl() {
        String configured = System.getProperty("app.db", System.getenv("DB_URL"));
        if (configured == null || configured.isBlank()) {
            return DEFAULT_DB_URL;
        }
        return configured.trim();
    }
}

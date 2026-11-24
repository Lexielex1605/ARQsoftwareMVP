package app.core.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Gestor embebido de conexiones SQLite e inicialización automática de schema.sql.
 * Vive en app.core.infrastructure para ser compartido por los repositorios de player y scoring
 * sin introducir dependencias entre módulos colaterales (RG-03).
 */
public final class SqliteDatabaseManager {

    private static final String DEFAULT_SCHEMA_RESOURCE = "/schema.sql";

    private final String jdbcUrl;

    public SqliteDatabaseManager(String jdbcUrl) {
        Objects.requireNonNull(jdbcUrl, "La URL JDBC de SQLite no puede ser nula");
        if (jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("La URL JDBC de SQLite no puede estar vacía");
        }
        this.jdbcUrl = jdbcUrl;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * Abre una nueva conexión SQLite con foreign_keys y busy_timeout habilitados.
     */
    public Connection openConnection() throws SQLException {
        Connection connection = DriverManager.getConnection(jdbcUrl);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON");
            stmt.execute("PRAGMA busy_timeout = 5000");
        }
        return connection;
    }

    /**
     * Carga y ejecuta automáticamente las sentencias DDL de src/main/resources/schema.sql.
     */
    public void initializeSchema() {
        List<String> statements = loadSchemaStatements(DEFAULT_SCHEMA_RESOURCE);
        try (Connection connection = openConnection()) {
            executeStatementsTransactionally(connection, statements);
            migrateMixedModeConstraintIfNeeded(connection);
            migrateAbandonedStatusConstraintIfNeeded(connection);
        } catch (SQLException ex) {
            throw new IllegalStateException("Error inicializando el esquema SQLite en " + jdbcUrl, ex);
        }
    }

    /**
     * Migra bases SQLite existentes para asegurar soporte de MIXED, hasta 16 jugadores
     * y la columna limite_cierre_apuntado en la tabla Partida.
     */
    private void migrateMixedModeConstraintIfNeeded(Connection connection) throws SQLException {
        String tableSql = readPartidaDefinition(connection);
        if (tableSql == null
                || (tableSql.contains("'MIXED'")
                && tableSql.contains("BETWEEN 2 AND 16")
                && tableSql.contains("limite_cierre_apuntado"))) {
            return;
        }
        boolean hasApuntadoLimitColumn = tableSql.contains("limite_cierre_apuntado");
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = OFF");
        }
        connection.setAutoCommit(false);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE Partida_migrated (
                        partida_id TEXT PRIMARY KEY,
                        codigo_sala TEXT NOT NULL UNIQUE CHECK (length(codigo_sala) = 6),
                        modalidad TEXT NOT NULL CHECK (modalidad IN ('CLASSIC', 'APUNTADO', 'MIXED')),
                        max_jugadores INTEGER NOT NULL CHECK (max_jugadores BETWEEN 2 AND 16),
                        num_barajas INTEGER NOT NULL CHECK (num_barajas BETWEEN 1 AND 4),
                        incluir_jokers INTEGER NOT NULL CHECK (incluir_jokers IN (0, 1)),
                        politica_valor TEXT NOT NULL,
                        politica_ciclo TEXT NOT NULL,
                        limite_eliminacion INTEGER NOT NULL CHECK (limite_eliminacion IN (51, 101, 151, 201, 251)),
                        limite_cierre_apuntado INTEGER NOT NULL DEFAULT 5 CHECK (limite_cierre_apuntado BETWEEN 1 AND 26),
                        estado TEXT NOT NULL DEFAULT 'ESPERANDO' CHECK (estado IN ('ESPERANDO', 'EN_CURSO', 'FINALIZADA')),
                        ganador_id TEXT REFERENCES Jugador(jugador_id),
                        creado_en TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        actualizado_en TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            String copySql = hasApuntadoLimitColumn
                    ? """
                      INSERT INTO Partida_migrated (
                          partida_id, codigo_sala, modalidad, max_jugadores, num_barajas,
                          incluir_jokers, politica_valor, politica_ciclo, limite_eliminacion,
                          limite_cierre_apuntado, estado, ganador_id, creado_en, actualizado_en
                      )
                      SELECT partida_id, codigo_sala, modalidad, max_jugadores, num_barajas,
                             incluir_jokers, politica_valor, politica_ciclo, limite_eliminacion,
                             limite_cierre_apuntado, estado, ganador_id, creado_en, actualizado_en
                      FROM Partida
                      """
                    : """
                      INSERT INTO Partida_migrated (
                          partida_id, codigo_sala, modalidad, max_jugadores, num_barajas,
                          incluir_jokers, politica_valor, politica_ciclo, limite_eliminacion,
                          limite_cierre_apuntado, estado, ganador_id, creado_en, actualizado_en
                      )
                      SELECT partida_id, codigo_sala, modalidad, max_jugadores, num_barajas,
                             incluir_jokers, politica_valor, politica_ciclo, limite_eliminacion,
                             5, estado, ganador_id, creado_en, actualizado_en
                      FROM Partida
                      """;
            stmt.execute(copySql);
            stmt.execute("DROP TABLE Partida");
            stmt.execute("ALTER TABLE Partida_migrated RENAME TO Partida");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_partida_codigo_sala ON Partida(codigo_sala)");
            connection.commit();
        } catch (SQLException ex) {
            rollbackQuietly(connection);
            throw ex;
        } finally {
            connection.setAutoCommit(true);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    private void migrateAbandonedStatusConstraintIfNeeded(Connection connection) throws SQLException {
        String tableSql = readTableDefinition(connection, "ParticipantePartida");
        if (tableSql == null || tableSql.contains("'ABANDONADO'")) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = OFF");
        }
        connection.setAutoCommit(false);
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("""
                    CREATE TABLE ParticipantePartida_migrated (
                        partida_id TEXT NOT NULL REFERENCES Partida(partida_id) ON DELETE CASCADE,
                        jugador_id TEXT NOT NULL REFERENCES Jugador(jugador_id) ON DELETE CASCADE,
                        asiento INTEGER NOT NULL CHECK (asiento >= 0),
                        puntaje_acumulado INTEGER NOT NULL DEFAULT 0,
                        estado TEXT NOT NULL DEFAULT 'ACTIVO' CHECK (estado IN ('ACTIVO', 'ELIMINADO', 'ABANDONADO')),
                        conectado INTEGER NOT NULL DEFAULT 1 CHECK (conectado IN (0, 1)),
                        es_anfitrion INTEGER NOT NULL DEFAULT 0 CHECK (es_anfitrion IN (0, 1)),
                        PRIMARY KEY (partida_id, jugador_id),
                        UNIQUE (partida_id, asiento)
                    )
                    """);
            stmt.execute("INSERT INTO ParticipantePartida_migrated SELECT * FROM ParticipantePartida");
            stmt.execute("DROP TABLE ParticipantePartida");
            stmt.execute("ALTER TABLE ParticipantePartida_migrated RENAME TO ParticipantePartida");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_participante_partida ON ParticipantePartida(partida_id)");
            connection.commit();
        } catch (SQLException ex) {
            rollbackQuietly(connection);
            throw ex;
        } finally {
            connection.setAutoCommit(true);
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    private String readPartidaDefinition(Connection connection) throws SQLException {
        return readTableDefinition(connection, "Partida");
    }

    private String readTableDefinition(Connection connection, String tableName) throws SQLException {
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT sql FROM sqlite_master WHERE type = 'table' AND name = '" + tableName + "'")) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private void executeStatementsTransactionally(Connection connection, List<String> statements)
            throws SQLException {
        connection.setAutoCommit(false);
        try (Statement stmt = connection.createStatement()) {
            for (String sql : statements) {
                stmt.execute(sql);
            }
            connection.commit();
        } catch (SQLException ex) {
            rollbackQuietly(connection);
            throw ex;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    private List<String> loadSchemaStatements(String resourcePath) {
        String rawSql = readClasspathResource(resourcePath);
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String line : rawSql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().trim();
                result.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        if (!current.toString().isBlank()) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private String readClasspathResource(String resourcePath) {
        try (InputStream input = SqliteDatabaseManager.class.getResourceAsStream(resourcePath)) {
            if (input == null) {
                throw new IllegalStateException("No se encontró el recurso de esquema: " + resourcePath);
            }
            StringBuilder builder = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }
            return builder.toString();
        } catch (IOException ex) {
            throw new IllegalStateException("Error leyendo el recurso " + resourcePath, ex);
        }
    }

    public static void rollbackQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // Se conserva la excepción original que disparó el rollback.
        }
    }
}

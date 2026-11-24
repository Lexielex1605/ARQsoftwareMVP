-- Esquema SQLite para Juego de Cartas (Clásica y Apuntado a 101)
-- Persistencia local ACID con claves primarias, foráneas y restricciones de integridad.

PRAGMA foreign_keys = ON;

CREATE TABLE IF NOT EXISTS Jugador (
    jugador_id TEXT PRIMARY KEY,
    nombre TEXT NOT NULL CHECK (length(trim(nombre)) > 0),
    token_sesion TEXT NOT NULL UNIQUE,
    creado_en TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS Partida (
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
);

CREATE TABLE IF NOT EXISTS ParticipantePartida (
    partida_id TEXT NOT NULL REFERENCES Partida(partida_id) ON DELETE CASCADE,
    jugador_id TEXT NOT NULL REFERENCES Jugador(jugador_id) ON DELETE CASCADE,
    asiento INTEGER NOT NULL CHECK (asiento >= 0),
    puntaje_acumulado INTEGER NOT NULL DEFAULT 0,
    estado TEXT NOT NULL DEFAULT 'ACTIVO' CHECK (estado IN ('ACTIVO', 'ELIMINADO', 'ABANDONADO')),
    conectado INTEGER NOT NULL DEFAULT 1 CHECK (conectado IN (0, 1)),
    es_anfitrion INTEGER NOT NULL DEFAULT 0 CHECK (es_anfitrion IN (0, 1)),
    PRIMARY KEY (partida_id, jugador_id),
    UNIQUE (partida_id, asiento)
);

CREATE TABLE IF NOT EXISTS Ronda (
    ronda_id INTEGER PRIMARY KEY AUTOINCREMENT,
    partida_id TEXT NOT NULL REFERENCES Partida(partida_id) ON DELETE CASCADE,
    numero_ronda INTEGER NOT NULL CHECK (numero_ronda >= 1),
    ganador_id TEXT NOT NULL REFERENCES Jugador(jugador_id),
    tipo_cierre TEXT NOT NULL,
    bono_cierre INTEGER NOT NULL DEFAULT 0,
    creada_en TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (partida_id, numero_ronda)
);

CREATE TABLE IF NOT EXISTS PuntajeRonda (
    puntaje_ronda_id INTEGER PRIMARY KEY AUTOINCREMENT,
    ronda_id INTEGER NOT NULL REFERENCES Ronda(ronda_id) ON DELETE CASCADE,
    jugador_id TEXT NOT NULL REFERENCES Jugador(jugador_id),
    delta_puntos INTEGER NOT NULL,
    puntaje_acumulado INTEGER NOT NULL,
    estado_resultante TEXT NOT NULL CHECK (estado_resultante IN ('ACTIVO', 'ELIMINADO')),
    UNIQUE (ronda_id, jugador_id)
);

CREATE INDEX IF NOT EXISTS idx_partida_codigo_sala ON Partida(codigo_sala);
CREATE INDEX IF NOT EXISTS idx_participante_partida ON ParticipantePartida(partida_id);
CREATE INDEX IF NOT EXISTS idx_ronda_partida ON Ronda(partida_id);
CREATE INDEX IF NOT EXISTS idx_puntaje_ronda_ronda ON PuntajeRonda(ronda_id);

package app.rules.engine;

/**
 * Tipos de combinación válida (juego armado) en la mano del jugador.
 */
public enum MeldType {
    /** Escalera de cartas del mismo palo en secuencia según CyclePolicy. */
    STRAIGHT,
    /** Terna o cuarta del mismo rango y palos distintos. */
    SET,
    /** Terna o cuarta del mismo rango con palos repetidos (espejos de múltiples barajas y/o Jokers). */
    MIRROR
}

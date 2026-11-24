package app.gameplay.orchestrator;

/**
 * Fase actual dentro del turno de un jugador.
 */
public enum TurnPhase {
    WAITING_START,
    MUST_DRAW,
    MUST_DISCARD,
    FINISHED
}

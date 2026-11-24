package app.scoring.service;

/**
 * Excepción de dominio lanzada cuando falla la persistencia transaccional de puntajes
 * y se ejecuta ROLLBACK en SQLite.
 */
public final class ScorePersistenceException extends RuntimeException {

    public ScorePersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    public ScorePersistenceException(String message) {
        super(message);
    }
}

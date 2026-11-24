package app.core;

/**
 * Modalidades de juego soportadas por el motor de reglas:
 * - CLASSIC: modalidad Clásica / Regular (bonos de -10 a -25 pts).
 * - APUNTADO: modalidad Apuntado a 101 (3 ternas + sobrante < 5 pts).
 * - MIXED: modalidad Mixta donde el jugador elige al cerrar si valida como Regular (CLASSIC) o Apuntado.
 */
public enum GameMode {
    CLASSIC("Clásica (Regular)"),
    APUNTADO("Apuntado"),
    MIXED("Mixto");

    private final String displayName;

    GameMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean requiresCloseModeSelection() {
        return this == MIXED;
    }

    public boolean isConcreteCloseMode() {
        return this == CLASSIC || this == APUNTADO;
    }

    /**
     * Resuelve la modalidad concreta (CLASSIC o APUNTADO) que debe usarse al declarar cierre.
     */
    public GameMode resolveCloseMode(GameMode requestedCloseMode) {
        if (this == MIXED) {
            if (requestedCloseMode == null || !requestedCloseMode.isConcreteCloseMode()) {
                throw new IllegalArgumentException(
                        "En modo Mixto debes elegir la modalidad de cierre: CLASSIC (Regular) o APUNTADO");
            }
            return requestedCloseMode;
        }
        if (requestedCloseMode != null && requestedCloseMode != this) {
            throw new IllegalArgumentException(
                    "La sala está configurada en modalidad " + this
                            + " y no admite cierre por " + requestedCloseMode);
        }
        return this;
    }
}

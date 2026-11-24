package app.core;

/**
 * Límites de puntos de eliminación soportados en la configuración de partida.
 */
public enum EliminationLimit {
    LIMIT_51(51),
    LIMIT_101(101),
    LIMIT_151(151),
    LIMIT_201(201),
    LIMIT_251(251);

    private final int points;

    EliminationLimit(int points) {
        this.points = points;
    }

    public int getPoints() {
        return points;
    }

    public boolean isEliminated(int accumulatedScore) {
        return accumulatedScore >= points;
    }

    public static EliminationLimit fromPoints(int points) {
        for (EliminationLimit limit : values()) {
            if (limit.points == points) {
                return limit;
            }
        }
        throw new IllegalArgumentException(
                "Límite de eliminación no soportado: " + points + ". Valores válidos: 51, 101, 151, 201, 251");
    }
}

package app.rules.engine;

import java.util.Objects;

/**
 * Resultado de evaluar si una mano cumple una condición válida de cierre.
 */
public record CloseEvaluation(
        boolean canClose,
        String closeType,
        int closeBonus,
        HandPartition partition
) {
    public CloseEvaluation {
        Objects.requireNonNull(closeType, "El tipo de cierre no puede ser nulo");
        Objects.requireNonNull(partition, "La partición de la mano no puede ser nula");
    }

    public static CloseEvaluation valid(String closeType, int closeBonus, HandPartition partition) {
        return new CloseEvaluation(true, closeType, closeBonus, partition);
    }

    public static CloseEvaluation invalid(HandPartition bestPartition) {
        return new CloseEvaluation(false, "NONE", 0, bestPartition);
    }
}

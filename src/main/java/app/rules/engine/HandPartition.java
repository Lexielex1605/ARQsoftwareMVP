package app.rules.engine;

import app.core.Card;

import java.util.List;
import java.util.Objects;

/**
 * Resultado de particionar una mano en combinaciones válidas y cartas sobrantes.
 */
public record HandPartition(
        List<Meld> melds,
        List<Card> deadwood,
        int deadwoodPoints
) {
    public HandPartition {
        Objects.requireNonNull(melds, "La lista de combinaciones no puede ser nula");
        Objects.requireNonNull(deadwood, "La lista de sobrantes no puede ser nula");
        if (deadwoodPoints < 0) {
            throw new IllegalArgumentException("El puntaje sobrante no puede ser negativo: " + deadwoodPoints);
        }
        melds = List.copyOf(melds);
        deadwood = List.copyOf(deadwood);
    }

    public boolean isComplete() {
        return deadwood.isEmpty();
    }

    public int meldedCardCount() {
        int total = 0;
        for (Meld meld : melds) {
            total += meld.size();
        }
        return total;
    }
}

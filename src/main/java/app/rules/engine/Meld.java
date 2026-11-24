package app.rules.engine;

import app.core.Card;

import java.util.List;
import java.util.Objects;

/**
 * Representa una combinación válida (terna, cuarta, espejo o escalera).
 */
public record Meld(MeldType type, List<Card> cards) {

    public Meld {
        Objects.requireNonNull(type, "El tipo de combinación no puede ser nulo");
        Objects.requireNonNull(cards, "Las cartas de la combinación no pueden ser nulas");
        if (cards.size() < 3) {
            throw new IllegalArgumentException(
                    "Una combinación válida debe tener al menos 3 cartas, recibido: " + cards.size());
        }
        cards = List.copyOf(cards);
    }

    public int size() {
        return cards.size();
    }
}

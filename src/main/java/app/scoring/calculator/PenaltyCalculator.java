package app.scoring.calculator;

import app.core.Card;
import app.core.CardValuePolicy;

import java.util.List;
import java.util.Objects;

/**
 * Calcula los puntos de penalización de cartas sobrantes aplicando CardValuePolicy.
 * Respeta RG-04 (CC <= 10) y RG-03 (solo depende de app.core).
 */
public final class PenaltyCalculator {

    public int calculateDeadwoodPenalty(List<Card> deadwoodCards, CardValuePolicy policy) {
        Objects.requireNonNull(deadwoodCards, "La lista de cartas sobrantes no puede ser nula");
        Objects.requireNonNull(policy, "La política de valor de cartas no puede ser nula");

        int total = 0;
        for (Card card : deadwoodCards) {
            total += calculateCardPenalty(card, policy);
        }
        return total;
    }

    public int calculateCardPenalty(Card card, CardValuePolicy policy) {
        Objects.requireNonNull(card, "La carta no puede ser nula");
        Objects.requireNonNull(policy, "La política de valor de cartas no puede ser nula");
        return policy.valueOf(card);
    }
}

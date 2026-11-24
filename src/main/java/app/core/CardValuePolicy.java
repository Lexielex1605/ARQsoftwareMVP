package app.core;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Política de valoración de cartas para cálculo de penalizaciones y cierres.
 * Encapsula la tabla de puntajes por política sin cadenas condicionales.
 */
public enum CardValuePolicy {
    STANDARD("Estándar (A=1, 2-10 nominal, J/Q/K=10, Joker=25)", 1, 10, 10, 10, 25),
    ACE_TEN("As en 10 (A=10, 2-10 nominal, J/Q/K=10, Joker=25)", 10, 10, 10, 10, 25),
    ACE_ELEVEN("As en 11 (A=11, 2-10 nominal, J/Q/K=10, Joker=25)", 11, 10, 10, 10, 25),
    FACE_SEQUENTIAL("Figuras secuenciales (A=1, J=11, Q=12, K=13, Joker=25)", 1, 11, 12, 13, 25);

    private final String description;
    private final Map<Rank, Integer> rankValues;

    CardValuePolicy(
            String description,
            int aceValue,
            int jackValue,
            int queenValue,
            int kingValue,
            int jokerValue
    ) {
        this.description = description;
        EnumMap<Rank, Integer> map = new EnumMap<>(Rank.class);
        for (Rank rank : Rank.values()) {
            map.put(rank, rank.getOrder());
        }
        map.put(Rank.ACE, aceValue);
        map.put(Rank.JACK, jackValue);
        map.put(Rank.QUEEN, queenValue);
        map.put(Rank.KING, kingValue);
        map.put(Rank.JOKER, jokerValue);
        this.rankValues = Map.copyOf(map);
    }

    public String getDescription() {
        return description;
    }

    public int valueOf(Rank rank) {
        Objects.requireNonNull(rank, "El rango no puede ser nulo");
        return rankValues.get(rank);
    }

    public int valueOf(Card card) {
        Objects.requireNonNull(card, "La carta no puede ser nula");
        return valueOf(card.rank());
    }
}

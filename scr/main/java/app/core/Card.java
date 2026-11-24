package app.core;

import java.util.Locale;
import java.util.Objects;

/**
 * Entidad inmutable compartida que representa una carta física dentro de una partida.
 * Vive en app.core para permitir su uso transversal sin violar RG-03.
 */
public record Card(
        String id,
        Suit suit,
        Rank rank,
        CardBack back,
        int deckNumber
) {
    private static final String ASSETS_BASE = "/assets/cards/";

    public Card {
        Objects.requireNonNull(id, "El id de la carta no puede ser nulo");
        Objects.requireNonNull(suit, "El palo de la carta no puede ser nulo");
        Objects.requireNonNull(rank, "El rango de la carta no puede ser nulo");
        Objects.requireNonNull(back, "El reverso de la carta no puede ser nulo");
        if (id.isBlank()) {
            throw new IllegalArgumentException("El id de la carta no puede estar vacío");
        }
        if (deckNumber < 1) {
            throw new IllegalArgumentException("El número de baraja debe ser >= 1, recibido: " + deckNumber);
        }
        if ((suit == Suit.JOKER) != (rank == Rank.JOKER)) {
            throw new IllegalArgumentException(
                    "Inconsistencia en carta Joker: suit=" + suit + ", rank=" + rank);
        }
    }

    public boolean isJoker() {
        return suit == Suit.JOKER;
    }

    /**
     * Devuelve la ruta web relativa al recurso PNG de la cara de la carta
     * bajo src/main/resources/static.
     */
    public String getImagePath() {
        if (isJoker()) {
            String jokerFile = id.toUpperCase(Locale.ROOT).contains("BLACK")
                    ? "card_joker_black.png"
                    : "card_joker_red.png";
            return ASSETS_BASE + suit.getFolderName() + "/" + jokerFile;
        }
        String folder = suit.getFolderName();
        return ASSETS_BASE + folder + "/card_" + folder + "_" + rank.getFileCode() + ".png";
    }

    /**
     * Devuelve la ruta web relativa al recurso PNG del reverso de la carta.
     */
    public String getBackImagePath() {
        return back.getImagePath();
    }
}

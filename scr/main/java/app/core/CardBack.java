package app.core;

/**
 * Color del reverso de una carta según la paridad de la baraja de origen:
 * barajas impares (1, 3) usan RED; barajas pares (2, 4) usan BLUE.
 */
public enum CardBack {
    RED("/assets/cards/cardback/cardback_red.png"),
    BLUE("/assets/cards/cardback/cardback_blue.png");

    private final String imagePath;

    CardBack(String imagePath) {
        this.imagePath = imagePath;
    }

    public String getImagePath() {
        return imagePath;
    }

    public static CardBack forDeckNumber(int deckNumber) {
        if (deckNumber < 1) {
            throw new IllegalArgumentException("El número de baraja debe ser >= 1, recibido: " + deckNumber);
        }
        return (deckNumber % 2 == 1) ? RED : BLUE;
    }
}

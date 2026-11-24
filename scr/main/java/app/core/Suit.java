package app.core;

/**
 * Palos disponibles en el mazo (incluyendo el palo especial para Jokers).
 */
public enum Suit {
    SPADES("spades", "♠"),
    HEARTS("hearts", "♥"),
    DIAMONDS("diamonds", "♦"),
    CLUBS("clubs", "♣"),
    JOKER("jokers", "★");

    private final String folderName;
    private final String symbol;

    Suit(String folderName, String symbol) {
        this.folderName = folderName;
        this.symbol = symbol;
    }

    public String getFolderName() {
        return folderName;
    }

    public String getSymbol() {
        return symbol;
    }

    public boolean isStandard() {
        return this != JOKER;
    }
}

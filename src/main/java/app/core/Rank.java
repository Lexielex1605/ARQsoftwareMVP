package app.core;

/**
 * Rangos de carta ordenados de As (1) a Rey (13), más el comodín JOKER (0).
 * El código de archivo coincide exactamente con los recursos PNG en static/assets/cards/.
 */
public enum Rank {
    ACE(1, "A"),
    TWO(2, "02"),
    THREE(3, "03"),
    FOUR(4, "04"),
    FIVE(5, "05"),
    SIX(6, "06"),
    SEVEN(7, "07"),
    EIGHT(8, "08"),
    NINE(9, "09"),
    TEN(10, "10"),
    JACK(11, "J"),
    QUEEN(12, "Q"),
    KING(13, "K"),
    JOKER(0, "joker");

    private final int order;
    private final String fileCode;

    Rank(int order, String fileCode) {
        this.order = order;
        this.fileCode = fileCode;
    }

    public int getOrder() {
        return order;
    }

    public String getFileCode() {
        return fileCode;
    }

    public boolean isStandard() {
        return this != JOKER;
    }

    public boolean isJoker() {
        return this == JOKER;
    }

    public boolean isFaceCard() {
        return this == JACK || this == QUEEN || this == KING;
    }
}

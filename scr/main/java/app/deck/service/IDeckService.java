package app.deck.service;

import app.core.Card;
import app.core.CardBack;
import app.core.GameConfig;

import java.util.List;
import java.util.Optional;

/**
 * Servicio de gestión del mazo de robo y pozo de descarte.
 * Pertenece al módulo colateral app.deck y solo depende de app.core (RG-03).
 */
public interface IDeckService {

    /**
     * Inicializa y baraja el mazo a partir de la configuración de partida.
     */
    void initialize(GameConfig config);

    /**
     * Inicializa y baraja entre 1 y 4 barajas con inclusión opcional de Jokers.
     */
    void initialize(int deckCount, boolean includeJokers);

    /**
     * Baraja el mazo de robo actual.
     */
    void shuffle();

    /**
     * Roba la carta superior del mazo. Si está vacío, recicla automáticamente
     * el pozo de descarte conservando la última carta visible.
     */
    Card drawFromDeck();

    /**
     * Roba la carta superior visible del pozo de descarte.
     */
    Card drawFromDiscard();

    /**
     * Coloca una carta en la cima del pozo de descarte.
     */
    void discard(Card card);

    /**
     * Consulta la carta superior visible del pozo de descarte sin retirarla.
     */
    Optional<Card> peekDiscard();

    /**
     * Consulta el color de reverso de la próxima carta del mazo sin revelar su cara.
     */
    Optional<CardBack> peekDeckBack();

    /**
     * Reparte una mano de {@code cardCount} cartas robándolas del mazo.
     */
    List<Card> dealHand(int cardCount);

    /**
     * Recicla el pozo de descarte hacia el mazo de robo conservando la última carta visible.
     *
     * @return {@code true} si se reciclaron cartas; {@code false} si había 1 o menos cartas en el pozo.
     */
    boolean recycleDiscardPile();

    int remainingDeckSize();

    int discardPileSize();
}

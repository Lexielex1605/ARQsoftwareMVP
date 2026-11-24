package unit.deck;

import app.core.Card;
import app.core.CardBack;
import app.core.GameConfig;
import app.deck.service.DeckManager;
import app.deck.service.IDeckService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DeckManagerTest {

    @ParameterizedTest
    @CsvSource({
            "1, false, 52",
            "2, false, 104",
            "3, false, 156",
            "4, false, 208",
            "1, true, 54",
            "2, true, 108",
            "3, true, 162",
            "4, true, 216"
    })
    void initializesExpectedCardCountForDecksAndJokers(int deckCount, boolean includeJokers, int expectedTotal) {
        IDeckService deck = new DeckManager(new Random(42L));
        deck.initialize(deckCount, includeJokers);

        assertEquals(expectedTotal, deck.remainingDeckSize());
        assertEquals(0, deck.discardPileSize());

        Set<String> seenIds = new HashSet<>();
        int jokerCount = 0;
        for (int i = 0; i < expectedTotal; i++) {
            Card card = deck.drawFromDeck();
            assertTrue(seenIds.add(card.id()), "ID duplicado encontrado: " + card.id());
            if (card.isJoker()) {
                jokerCount++;
            }
        }
        assertEquals(includeJokers ? deckCount * 2 : 0, jokerCount);
    }

    @Test
    void assignsRedBackToOddDecksAndBlueBackToEvenDecks() {
        IDeckService deck = new DeckManager(new Random(7L));
        deck.initialize(4, true);

        int redCount = 0;
        int blueCount = 0;
        while (deck.remainingDeckSize() > 0) {
            Card card = deck.drawFromDeck();
            CardBack expectedBack = (card.deckNumber() % 2 == 1) ? CardBack.RED : CardBack.BLUE;
            assertEquals(expectedBack, card.back(),
                    "Reverso incorrecto para carta de baraja " + card.deckNumber());
            if (card.back() == CardBack.RED) {
                redCount++;
            } else {
                blueCount++;
            }
        }
        assertEquals(108, redCount);
        assertEquals(108, blueCount);
    }

    @Test
    void everyGeneratedCardMapsToAnExistingStaticImageAsset() {
        IDeckService deck = new DeckManager(new Random(123L));
        deck.initialize(2, true);

        Path staticRoot = Paths.get("src/main/resources/static");
        while (deck.remainingDeckSize() > 0) {
            Card card = deck.drawFromDeck();
            Path faceAsset = staticRoot.resolve(card.getImagePath().substring(1));
            Path backAsset = staticRoot.resolve(card.getBackImagePath().substring(1));

            assertTrue(Files.isRegularFile(faceAsset),
                    "No existe la imagen de cara para " + card + ": " + faceAsset);
            assertTrue(Files.isRegularFile(backAsset),
                    "No existe la imagen de reverso para " + card + ": " + backAsset);
        }
    }

    @Test
    void autoRecyclesDiscardPileKeepingTopVisibleCard() {
        IDeckService deck = new DeckManager(new Random(99L));
        deck.initialize(1, false);

        List<Card> discardedOrder = new ArrayList<>();
        while (deck.remainingDeckSize() > 0) {
            Card drawn = deck.drawFromDeck();
            deck.discard(drawn);
            discardedOrder.add(drawn);
        }

        assertEquals(0, deck.remainingDeckSize());
        assertEquals(52, deck.discardPileSize());

        Card topVisibleBeforeRecycle = discardedOrder.get(discardedOrder.size() - 1);
        assertEquals(Optional.of(topVisibleBeforeRecycle), deck.peekDiscard());

        // Robar con el mazo vacío debe reciclar 51 cartas y dejar intacta la última visible
        Card drawnAfterRecycle = deck.drawFromDeck();

        assertNotEquals(topVisibleBeforeRecycle, drawnAfterRecycle);
        assertEquals(Optional.of(topVisibleBeforeRecycle), deck.peekDiscard());
        assertEquals(1, deck.discardPileSize());
        assertEquals(50, deck.remainingDeckSize());

        // Verificar conservación total de cartas sin pérdidas ni duplicados
        Set<String> recoveredIds = new HashSet<>();
        recoveredIds.add(topVisibleBeforeRecycle.id());
        recoveredIds.add(drawnAfterRecycle.id());
        while (deck.remainingDeckSize() > 0) {
            assertTrue(recoveredIds.add(deck.drawFromDeck().id()));
        }
        assertEquals(52, recoveredIds.size());
    }

    @Test
    void doesNotRecycleWhenDiscardPileHasOnlyOneCardAndThrowsOnEmptyDraw() {
        IDeckService deck = new DeckManager(new Random(11L));
        deck.initialize(1, false);

        List<Card> hand = deck.dealHand(51);
        assertEquals(51, hand.size());

        Card lastCard = deck.drawFromDeck();
        deck.discard(lastCard);

        assertEquals(0, deck.remainingDeckSize());
        assertEquals(1, deck.discardPileSize());
        assertFalse(deck.recycleDiscardPile());
        assertEquals(Optional.of(lastCard), deck.peekDiscard());
        assertThrows(IllegalStateException.class, deck::drawFromDeck);
    }

    @Test
    void drawFromDiscardReturnsAndRemovesTopCard() {
        IDeckService deck = new DeckManager(new Random(5L));
        deck.initialize(1, false);

        Card first = deck.drawFromDeck();
        Card second = deck.drawFromDeck();
        deck.discard(first);
        deck.discard(second);

        assertEquals(Optional.of(second), deck.peekDiscard());
        assertEquals(second, deck.drawFromDiscard());
        assertEquals(Optional.of(first), deck.peekDiscard());
        assertEquals(first, deck.drawFromDiscard());
        assertTrue(deck.peekDiscard().isEmpty());
        assertThrows(IllegalStateException.class, deck::drawFromDiscard);
    }

    @Test
    void initializesFromGameConfigAndExposesNextCardBack() {
        GameConfig config = new GameConfig();
        config.setMaxPlayers(6);
        config.setDeckCount(3);
        config.setIncludeJokers(false);

        IDeckService deck = new DeckManager(new Random(21L));
        deck.initialize(config);

        assertEquals(156, deck.remainingDeckSize());
        Optional<CardBack> nextBack = deck.peekDeckBack();
        assertTrue(nextBack.isPresent());
        Card drawn = deck.drawFromDeck();
        assertEquals(nextBack.get(), drawn.back());
    }

    @Test
    void rejectsInvalidArguments() {
        IDeckService deck = new DeckManager();
        assertThrows(IllegalArgumentException.class, () -> deck.initialize(0, true));
        assertThrows(IllegalArgumentException.class, () -> deck.initialize(5, true));
        assertThrows(NullPointerException.class, () -> deck.initialize(null));
        assertThrows(NullPointerException.class, () -> deck.discard(null));

        deck.initialize(1, false);
        assertThrows(IllegalArgumentException.class, () -> deck.dealHand(0));
        assertThrows(IllegalArgumentException.class, () -> deck.dealHand(-3));
    }
}

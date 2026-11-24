package unit.rules;

import app.core.Card;
import app.core.CardBack;
import app.core.CardValuePolicy;
import app.core.CyclePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.core.GameMode;
import app.core.Rank;
import app.core.Suit;
import app.rules.engine.HandPartition;
import app.rules.engine.HandPartitioner;
import app.rules.engine.MeldType;
import app.rules.engine.MeldValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HandPartitionerTest {

    private final MeldValidator validator = new MeldValidator();
    private final HandPartitioner partitioner = new HandPartitioner(validator);

    private static Card card(Suit suit, Rank rank, int deck) {
        return new Card("D" + deck + "-" + suit + "-" + rank, suit, rank, CardBack.forDeckNumber(deck), deck);
    }

    private static Card joker(int deck, String color) {
        return new Card("D" + deck + "-JOKER-" + color, Suit.JOKER, Rank.JOKER, CardBack.forDeckNumber(deck), deck);
    }

    @Test
    void validatesStraightsSetsMirrorsAndRejectsMixedDuplicates() {
        // Escalera cíclica K-A-2
        List<Card> cyclicStraight = List.of(
                card(Suit.SPADES, Rank.KING, 1),
                card(Suit.SPADES, Rank.ACE, 1),
                card(Suit.SPADES, Rank.TWO, 1)
        );
        assertEquals(Optional.of(MeldType.STRAIGHT), validator.classify(cyclicStraight, CyclePolicy.CYCLIC));
        assertTrue(validator.classify(cyclicStraight, CyclePolicy.LINEAR).isEmpty());
        assertTrue(validator.classify(cyclicStraight, CyclePolicy.LINEAR_ACE_LOW).isEmpty());

        // Terna de palos distintos
        List<Card> set = List.of(
                card(Suit.SPADES, Rank.SEVEN, 1),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1)
        );
        assertEquals(Optional.of(MeldType.SET), validator.classify(set, CyclePolicy.LINEAR));

        // Espejo (mismo palo y mismo rango con varias barajas o Joker)
        List<Card> mirrorWithJoker = List.of(
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.HEARTS, Rank.KING, 2),
                joker(1, "RED")
        );
        assertEquals(Optional.of(MeldType.MIRROR), validator.classify(mirrorWithJoker, CyclePolicy.LINEAR));

        List<Card> threeDeckMirror = List.of(
                card(Suit.CLUBS, Rank.NINE, 1),
                card(Suit.CLUBS, Rank.NINE, 2),
                card(Suit.CLUBS, Rank.NINE, 3)
        );
        assertEquals(Optional.of(MeldType.MIRROR), validator.classify(threeDeckMirror, CyclePolicy.LINEAR));

        // Terna con espejo (dos cartas iguales del mismo palo + otro palo del mismo rango)
        List<Card> setWithMirror = List.of(
                card(Suit.SPADES, Rank.SEVEN, 1),
                card(Suit.SPADES, Rank.SEVEN, 2),
                card(Suit.HEARTS, Rank.SEVEN, 1)
        );
        assertEquals(Optional.of(MeldType.MIRROR), validator.classify(setWithMirror, CyclePolicy.CYCLIC));

        // Cuarta con espejo (6♠, 6♠, 6♥, 6♣)
        List<Card> quadWithMirror = List.of(
                card(Suit.SPADES, Rank.SIX, 1),
                card(Suit.SPADES, Rank.SIX, 2),
                card(Suit.HEARTS, Rank.SIX, 1),
                card(Suit.CLUBS, Rank.SIX, 1)
        );
        assertEquals(Optional.of(MeldType.MIRROR), validator.classify(quadWithMirror, CyclePolicy.CYCLIC));

        // Combinación inválida: rangos distintos y no forman escalera
        List<Card> invalidGroup = List.of(
                card(Suit.SPADES, Rank.SEVEN, 1),
                card(Suit.SPADES, Rank.SEVEN, 2),
                card(Suit.SPADES, Rank.EIGHT, 1)
        );
        assertFalse(validator.isValidMeld(invalidGroup, CyclePolicy.CYCLIC));
    }

    @Test
    void choosesPartitionThatMinimizesDeadwoodPointsWhenMeldsOverlap() {
        // 10♠ puede ir en escalera [8♠, 9♠, 10♠] (deja 10♥ y 10♦ sobrantes = 20 pts)
        // O puede ir en terna [10♠, 10♥, 10♦] (deja 8♠ y 9♠ sobrantes = 17 pts) -> ¡mejor 17 pts!
        List<Card> hand = List.of(
                card(Suit.SPADES, Rank.EIGHT, 1),
                card(Suit.SPADES, Rank.NINE, 1),
                card(Suit.SPADES, Rank.TEN, 1),
                card(Suit.HEARTS, Rank.TEN, 1),
                card(Suit.DIAMONDS, Rank.TEN, 1)
        );
        GameConfig config = new GameConfig(
                GameMode.CLASSIC, 4, 2, true,
                CardValuePolicy.STANDARD, CyclePolicy.CYCLIC, EliminationLimit.LIMIT_101
        );

        HandPartition best = partitioner.findBestPartition(hand, config);
        assertEquals(1, best.melds().size());
        assertEquals(MeldType.SET, best.melds().get(0).type());
        assertEquals(2, best.deadwood().size());
        assertEquals(17, best.deadwoodPoints());
    }

    @Test
    void usesJokerOptimallyToCompleteHighValueMeld() {
        // Joker debe usarse con Q♠ y K♠ (ahorra 20 pts) y no con 2♣ y 3♣ (ahorraría 5 pts)
        List<Card> hand = List.of(
                card(Suit.SPADES, Rank.QUEEN, 1),
                card(Suit.SPADES, Rank.KING, 1),
                card(Suit.CLUBS, Rank.TWO, 1),
                card(Suit.CLUBS, Rank.THREE, 1),
                joker(1, "RED")
        );
        GameConfig config = new GameConfig();

        HandPartition best = partitioner.findBestPartition(hand, config);
        assertEquals(1, best.melds().size());
        assertEquals(5, best.deadwoodPoints());
    }

    @Test
    void handlesIdenticalMultiDeckCardsUsedAcrossSeparateMelds() {
        // Dos 7♠ de barajas distintas (D1 y D2) usados en dos juegos diferentes:
        // Escalera [7♠(D1), 8♠(D1), 9♠(D1)] + Terna [7♠(D2), 7♥(D1), 7♦(D1)] + Cuarta [K♠, K♥, K♦, K♣]
        List<Card> hand = List.of(
                card(Suit.SPADES, Rank.SEVEN, 1),
                card(Suit.SPADES, Rank.EIGHT, 1),
                card(Suit.SPADES, Rank.NINE, 1),
                card(Suit.SPADES, Rank.SEVEN, 2),
                card(Suit.HEARTS, Rank.SEVEN, 1),
                card(Suit.DIAMONDS, Rank.SEVEN, 1),
                card(Suit.SPADES, Rank.KING, 1),
                card(Suit.HEARTS, Rank.KING, 1),
                card(Suit.DIAMONDS, Rank.KING, 1),
                card(Suit.CLUBS, Rank.KING, 1)
        );
        GameConfig config = new GameConfig();

        HandPartition best = partitioner.findBestPartition(hand, config);
        assertTrue(best.isComplete());
        assertEquals(0, best.deadwoodPoints());
        assertEquals(3, best.melds().size());
    }
}

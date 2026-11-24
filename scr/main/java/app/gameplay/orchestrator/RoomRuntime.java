package app.gameplay.orchestrator;

import app.core.Card;
import app.core.CardBack;
import app.core.GameConfig;
import app.deck.service.IDeckService;
import app.player.Player;
import app.scoring.service.IScoreService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Estado en memoria de una sala en ejecución (baraja activa, manos, turno y resumen de ronda).
 * Encapsula la mutación controlada de rondas bajo el monitor de sincronización del orquestador.
 */
final class RoomRuntime {

    private final IDeckService deckService;
    private final IScoreService scoreService;
    private final Map<String, List<Card>> hands = new LinkedHashMap<>();

    private List<String> turnOrder = List.of();
    private int roundNumber = 0;
    private int currentTurnIndex = 0;
    private int roundStarterCursor = 0;
    private TurnPhase turnPhase = TurnPhase.WAITING_START;
    private String forbiddenDiscardCardId;
    private RoundSummary lastRoundSummary;

    RoomRuntime(IDeckService deckService, IScoreService scoreService) {
        this.deckService = Objects.requireNonNull(deckService, "El servicio de mazo no puede ser nulo");
        this.scoreService = Objects.requireNonNull(scoreService, "El servicio de puntaje no puede ser nulo");
    }

    IDeckService getDeckService() {
        return deckService;
    }

    IScoreService getScoreService() {
        return scoreService;
    }

    int getRoundNumber() {
        return roundNumber;
    }

    TurnPhase getTurnPhase() {
        return turnPhase;
    }

    String getForbiddenDiscardCardId() {
        return forbiddenDiscardCardId;
    }

    RoundSummary getLastRoundSummary() {
        return lastRoundSummary;
    }

    String getCurrentTurnPlayerId() {
        if (turnOrder.isEmpty() || turnPhase == TurnPhase.FINISHED) {
            return null;
        }
        return turnOrder.get(currentTurnIndex);
    }

    List<Card> getHandCopy(String playerId) {
        List<Card> hand = hands.get(playerId);
        return hand == null ? List.of() : List.copyOf(hand);
    }

    List<CardBack> getHandBacks(String playerId) {
        List<Card> hand = hands.get(playerId);
        if (hand == null || hand.isEmpty()) {
            return List.of();
        }
        List<CardBack> backs = new ArrayList<>(hand.size());
        for (Card card : hand) {
            backs.add(card.back());
        }
        return Collections.unmodifiableList(backs);
    }

    List<String> getActiveRoundPlayerIds() {
        return List.copyOf(turnOrder);
    }

    void startNextRound(List<Player> activePlayers, GameConfig config) {
        roundNumber++;
        deckService.initialize(config);
        hands.clear();

        List<String> order = new ArrayList<>(activePlayers.size());
        for (Player player : activePlayers) {
            order.add(player.getPlayerId());
            hands.put(player.getPlayerId(), new ArrayList<>(deckService.dealHand(GameConfig.HAND_SIZE)));
        }
        deckService.discard(deckService.drawFromDeck());

        this.turnOrder = List.copyOf(order);
        this.currentTurnIndex = roundStarterCursor % turnOrder.size();
        this.roundStarterCursor++;
        this.turnPhase = TurnPhase.MUST_DRAW;
        this.forbiddenDiscardCardId = null;
    }

    void addCardToHand(String playerId, Card card, boolean drawnFromDiscard) {
        requirePlayerHand(playerId).add(card);
        this.turnPhase = TurnPhase.MUST_DISCARD;
        this.forbiddenDiscardCardId = drawnFromDiscard ? card.id() : null;
    }

    Card removeCardFromHand(String playerId, String cardId) {
        List<Card> hand = requirePlayerHand(playerId);
        int index = findCardIndex(hand, cardId);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "La carta '" + cardId + "' no se encuentra en la mano del jugador " + playerId);
        }
        return hand.remove(index);
    }

    List<Card> previewHandWithout(String playerId, String cardId) {
        List<Card> hand = requirePlayerHand(playerId);
        int index = findCardIndex(hand, cardId);
        if (index < 0) {
            throw new IllegalArgumentException(
                    "La carta '" + cardId + "' no se encuentra en la mano del jugador " + playerId);
        }
        List<Card> copy = new ArrayList<>(hand.size() - 1);
        for (int i = 0; i < hand.size(); i++) {
            if (i != index) {
                copy.add(hand.get(i));
            }
        }
        return copy;
    }

    void advanceTurn() {
        currentTurnIndex = (currentTurnIndex + 1) % turnOrder.size();
        turnPhase = TurnPhase.MUST_DRAW;
        forbiddenDiscardCardId = null;
    }

    void removePlayer(String playerId) {
        hands.remove(playerId);
        int idx = turnOrder.indexOf(playerId);
        if (idx < 0) {
            return;
        }
        List<String> updated = new ArrayList<>(turnOrder);
        updated.remove(idx);
        this.turnOrder = List.copyOf(updated);

        if (turnOrder.isEmpty()) {
            currentTurnIndex = 0;
            forbiddenDiscardCardId = null;
            return;
        }
        if (idx < currentTurnIndex) {
            currentTurnIndex--;
        } else if (idx == currentTurnIndex) {
            currentTurnIndex = currentTurnIndex % turnOrder.size();
            turnPhase = TurnPhase.MUST_DRAW;
            forbiddenDiscardCardId = null;
        }
    }

    void recordRoundSummary(RoundSummary summary) {
        this.lastRoundSummary = summary;
    }

    void markFinished(RoundSummary summary) {
        this.lastRoundSummary = summary;
        this.turnPhase = TurnPhase.FINISHED;
        this.forbiddenDiscardCardId = null;
    }

    private List<Card> requirePlayerHand(String playerId) {
        List<Card> hand = hands.get(playerId);
        if (hand == null) {
            throw new IllegalStateException("El jugador " + playerId + " no tiene mano activa en esta ronda");
        }
        return hand;
    }

    private int findCardIndex(List<Card> hand, String cardId) {
        Objects.requireNonNull(cardId, "El cardId no puede ser nulo");
        for (int i = 0; i < hand.size(); i++) {
            if (hand.get(i).id().equals(cardId)) {
                return i;
            }
        }
        return -1;
    }
}

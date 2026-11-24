package app.gameplay.orchestrator;

import app.core.Card;
import app.core.CardBack;
import app.core.GameConfig;
import app.core.GameMode;
import app.deck.service.IDeckService;
import app.player.JoinResult;
import app.player.MatchStatus;
import app.player.Player;
import app.player.RoomSession;
import app.player.service.IPlayerService;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.IRuleEngine;
import app.scoring.service.IScoreService;
import app.scoring.service.RoundHistoryEntry;
import app.scoring.service.RoundScoreResult;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Mediador central y síncrono de partidas (RG-01, RG-03).
 * Coordina exclusivamente interfaces de los módulos colaterales (IDeckService, IRuleEngine,
 * IScoreService, IPlayerService) sin que dichos módulos se comuniquen entre sí.
 */
public final class GameOrchestrator implements IGameController {

    private final Supplier<IDeckService> deckServiceFactory;
    private final IRuleEngine ruleEngine;
    private final Supplier<IScoreService> scoreServiceFactory;
    private final IPlayerService playerService;
    private final Map<String, RoomRuntime> activeRooms = new ConcurrentHashMap<>();

    public GameOrchestrator(
            Supplier<IDeckService> deckServiceFactory,
            IRuleEngine ruleEngine,
            Supplier<IScoreService> scoreServiceFactory,
            IPlayerService playerService
    ) {
        this.deckServiceFactory = Objects.requireNonNull(deckServiceFactory, "deckServiceFactory no puede ser nulo");
        this.ruleEngine = Objects.requireNonNull(ruleEngine, "ruleEngine no puede ser nulo");
        this.scoreServiceFactory = Objects.requireNonNull(scoreServiceFactory, "scoreServiceFactory no puede ser nulo");
        this.playerService = Objects.requireNonNull(playerService, "playerService no puede ser nulo");
    }

    @Override
    public JoinResult createRoom(String hostName, GameConfig config) {
        return playerService.createRoom(hostName, config);
    }

    @Override
    public JoinResult joinRoom(String roomCode, String playerName) {
        return playerService.joinRoom(roomCode, playerName);
    }

    @Override
    public SessionTicket createRoomSession(String hostName, GameConfig config) {
        return toSessionTicket(createRoom(hostName, config));
    }

    @Override
    public SessionTicket joinRoomSession(String roomCode, String playerName) {
        return toSessionTicket(joinRoom(roomCode, playerName));
    }

    @Override
    public SessionTicket reconnectSession(String roomCode, String sessionToken, String playerId) {
        String code = normalizeCode(roomCode);
        Player player = (sessionToken != null && !sessionToken.isBlank())
                ? reconnect(code, sessionToken)
                : reconnectByPlayerId(code, playerId);
        RoomSession room = playerService.getRoom(code);
        return new SessionTicket(
                room.matchId(),
                room.roomCode(),
                player.getPlayerId(),
                player.getName(),
                player.getSessionToken(),
                player.getSeatIndex(),
                player.isHost(),
                room.config()
        );
    }

    @Override
    public RoomOverview getRoomOverview(String roomCode) {
        String code = normalizeCode(roomCode);
        RoomSession room = playerService.getRoom(code);
        RoomRuntime runtime = activeRooms.get(code);
        List<OpponentView> players = (runtime != null)
                ? buildOpponentViews(room.players(), runtime)
                : buildLobbyOpponentViews(room.players());
        int activeCount = (int) room.players().stream().filter(Player::isActive).count();
        boolean canStart = room.status() == MatchStatus.ESPERANDO
                && activeCount >= GameConfig.MIN_PLAYERS
                && room.config().isDeckCountSufficientFor(activeCount);
        return new RoomOverview(
                room.matchId(),
                room.roomCode(),
                room.status().name(),
                room.config(),
                room.players().size(),
                room.config().getMaxPlayers(),
                canStart,
                players
        );
    }

    @Override
    public Player reconnect(String roomCode, String sessionToken) {
        return playerService.reconnect(roomCode, sessionToken);
    }

    @Override
    public Player reconnectByPlayerId(String roomCode, String playerId) {
        return playerService.reconnectByPlayerId(roomCode, playerId);
    }

    @Override
    public void disconnect(String roomCode, String playerId) {
        playerService.disconnect(roomCode, playerId);
    }

    @Override
    public LeaveResult leaveRoom(String roomCode, String playerId) {
        String code = normalizeCode(roomCode);
        Player left = playerService.abandonRoom(code, playerId);
        RoomSession room = playerService.getRoom(code);
        RoomRuntime runtime = activeRooms.get(code);

        if (runtime == null || room.status() != MatchStatus.EN_CURSO) {
            return new LeaveResult(
                    code, left.getPlayerId(), left.getName(),
                    false, null, null,
                    left.getName() + " abandonó la sala", null
            );
        }

        synchronized (runtime) {
            runtime.removePlayer(playerId);
            return evaluatePostAbandonment(code, runtime, left);
        }
    }

    @Override
    public void updateConfig(String roomCode, String requesterPlayerId, GameConfig newConfig) {
        playerService.updateConfig(roomCode, requesterPlayerId, newConfig);
    }

    @Override
    public PlayerGameView startGame(String roomCode, String requesterPlayerId) {
        String code = normalizeCode(roomCode);
        RoomSession room = playerService.getRoom(code);
        verifyHost(room, requesterPlayerId);
        playerService.validateCanStart(code);

        RoomRuntime runtime = new RoomRuntime(deckServiceFactory.get(), scoreServiceFactory.get());
        synchronized (runtime) {
            registerPlayersInScoreService(runtime.getScoreService(), room.players());
            playerService.markMatchStarted(code);
            List<Player> activePlayers = playerService.getActivePlayers(code);
            runtime.startNextRound(activePlayers, room.config());
            activeRooms.put(code, runtime);
            return buildPlayerView(playerService.getRoom(code), runtime, requesterPlayerId);
        }
    }

    @Override
    public PlayerGameView drawFromDeck(String roomCode, String playerId) {
        String code = normalizeCode(roomCode);
        RoomRuntime runtime = requireRuntime(code);
        synchronized (runtime) {
            verifyTurnAndPhase(runtime, playerId, TurnPhase.MUST_DRAW);
            Card drawn = runtime.getDeckService().drawFromDeck();
            runtime.addCardToHand(playerId, drawn, false);
            return buildPlayerView(playerService.getRoom(code), runtime, playerId);
        }
    }

    @Override
    public PlayerGameView drawFromDiscard(String roomCode, String playerId) {
        String code = normalizeCode(roomCode);
        RoomRuntime runtime = requireRuntime(code);
        synchronized (runtime) {
            verifyTurnAndPhase(runtime, playerId, TurnPhase.MUST_DRAW);
            Card drawn = runtime.getDeckService().drawFromDiscard();
            runtime.addCardToHand(playerId, drawn, true);
            return buildPlayerView(playerService.getRoom(code), runtime, playerId);
        }
    }

    @Override
    public PlayerGameView discard(String roomCode, String playerId, String cardId) {
        String code = normalizeCode(roomCode);
        RoomRuntime runtime = requireRuntime(code);
        synchronized (runtime) {
            verifyTurnAndPhase(runtime, playerId, TurnPhase.MUST_DISCARD);
            if (Objects.equals(cardId, runtime.getForbiddenDiscardCardId())) {
                throw new IllegalStateException(
                        "No puedes descartar la misma carta que acabas de tomar del pozo de descarte");
            }
            Card discarded = runtime.removeCardFromHand(playerId, cardId);
            runtime.getDeckService().discard(discarded);
            runtime.advanceTurn();
            return buildPlayerView(playerService.getRoom(code), runtime, playerId);
        }
    }

    @Override
    public RoundSummary declareClose(String roomCode, String playerId, String optionalDiscardCardId) {
        return declareClose(roomCode, playerId, optionalDiscardCardId, null);
    }

    @Override
    public RoundSummary declareClose(
            String roomCode,
            String playerId,
            String optionalDiscardCardId,
            GameMode requestedCloseMode
    ) {
        String code = normalizeCode(roomCode);
        RoomRuntime runtime = requireRuntime(code);
        synchronized (runtime) {
            RoomSession room = playerService.getRoom(code);
            verifyPlayerTurnForClose(room, runtime, playerId);
            if (runtime.getTurnPhase() != TurnPhase.MUST_DRAW) {
                throw new IllegalStateException(
                        "No puedes declarar cierre después de haber robado una carta; debes descartar y declarar cierre al inicio de tu próximo turno");
            }

            GameMode effectiveCloseMode = room.config().getGameMode().resolveCloseMode(requestedCloseMode);
            List<Card> candidateHand = runtime.getHandCopy(playerId);
            CloseEvaluation evaluation = ruleEngine.evaluateClose(candidateHand, room.config(), effectiveCloseMode);
            if (!evaluation.canClose()) {
                throw new IllegalStateException(
                        "Cierre inválido para modalidad " + effectiveCloseMode.getDisplayName()
                                + " (sobrante actual: " + evaluation.partition().deadwoodPoints() + " pts)");
            }

            Map<String, List<Card>> opponentDeadwood = collectOpponentDeadwood(runtime, playerId, room.config());
            Map<String, RoundScoreResult> settled = runtime.getScoreService().settleAndPersistRound(
                    room.matchId(),
                    runtime.getRoundNumber(),
                    playerId,
                    evaluation.closeType(),
                    evaluation.closeBonus(),
                    opponentDeadwood,
                    room.config()
            );

            return finalizeRoundOrMatch(code, runtime, playerId, evaluation, settled);
        }
    }

    @Override
    public PlayerGameView getPlayerView(String roomCode, String viewerPlayerId) {
        String code = normalizeCode(roomCode);
        RoomSession room = playerService.getRoom(code);
        verifyMember(room, viewerPlayerId);
        RoomRuntime runtime = activeRooms.get(code);
        if (runtime == null) {
            return buildLobbyView(room, viewerPlayerId);
        }
        synchronized (runtime) {
            return buildPlayerView(room, runtime, viewerPlayerId);
        }
    }

    @Override
    public RoomSession getRoomSession(String roomCode) {
        return playerService.getRoom(normalizeCode(roomCode));
    }

    @Override
    public List<RoundHistoryEntry> getRoundHistory(String roomCode) {
        String code = normalizeCode(roomCode);
        RoomSession room = playerService.getRoom(code);
        RoomRuntime runtime = activeRooms.get(code);
        IScoreService scoreService = runtime != null ? runtime.getScoreService() : scoreServiceFactory.get();
        return scoreService.getRoundHistory(room.matchId());
    }

    private Map<String, List<Card>> collectOpponentDeadwood(
            RoomRuntime runtime,
            String winnerId,
            GameConfig config
    ) {
        Map<String, List<Card>> deadwoodByPlayer = new LinkedHashMap<>();
        for (String participantId : runtime.getActiveRoundPlayerIds()) {
            if (participantId.equals(winnerId)) {
                continue;
            }
            List<Card> hand = runtime.getHandCopy(participantId);
            deadwoodByPlayer.put(participantId, ruleEngine.evaluateHand(hand, config).deadwood());
        }
        return deadwoodByPlayer;
    }

    private LeaveResult evaluatePostAbandonment(String roomCode, RoomRuntime runtime, Player left) {
        List<Player> remainingActive = playerService.getActivePlayers(roomCode);
        if (remainingActive.size() == 1) {
            Player winner = remainingActive.get(0);
            playerService.markMatchFinished(roomCode, winner.getPlayerId());
            List<RoundScoreResult> standings = buildStandingsSnapshot(playerService.getRoom(roomCode).players());
            RoundSummary victorySummary = new RoundSummary(
                    runtime.getRoundNumber(),
                    winner.getPlayerId(),
                    "VICTORIA_POR_ABANDONO",
                    0,
                    standings,
                    true,
                    winner.getPlayerId()
            );
            runtime.markFinished(victorySummary);
            return new LeaveResult(
                    roomCode, left.getPlayerId(), left.getName(),
                    true, winner.getPlayerId(), winner.getName(),
                    left.getName() + " abandonó la partida. ¡Victoria por abandono para " + winner.getName() + "!",
                    victorySummary
            );
        }
        if (remainingActive.isEmpty()) {
            playerService.markMatchFinished(roomCode, null);
            runtime.markFinished(null);
            return new LeaveResult(
                    roomCode, left.getPlayerId(), left.getName(),
                    true, null, null,
                    left.getName() + " abandonó la partida. Todos los jugadores abandonaron.",
                    null
            );
        }
        return new LeaveResult(
                roomCode, left.getPlayerId(), left.getName(),
                false, null, null,
                left.getName() + " abandonó la partida. El juego continúa con el resto de jugadores.",
                null
        );
    }

    private List<RoundScoreResult> buildStandingsSnapshot(List<Player> players) {
        List<RoundScoreResult> snapshot = new ArrayList<>(players.size());
        for (Player player : players) {
            snapshot.add(new RoundScoreResult(
                    player.getPlayerId(),
                    0,
                    player.getAccumulatedScore(),
                    !player.isActive()
            ));
        }
        return snapshot;
    }

    private RoundSummary finalizeRoundOrMatch(
            String roomCode,
            RoomRuntime runtime,
            String roundWinnerId,
            CloseEvaluation evaluation,
            Map<String, RoundScoreResult> settled
    ) {
        int finishedRoundNumber = runtime.getRoundNumber();
        List<Player> remainingActive = playerService.getActivePlayers(roomCode);
        List<RoundScoreResult> scoreList = List.copyOf(settled.values());

        if (remainingActive.size() <= 1) {
            String matchWinnerId = resolveMatchWinner(roomCode, remainingActive);
            playerService.markMatchFinished(roomCode, matchWinnerId);
            RoundSummary summary = new RoundSummary(
                    finishedRoundNumber, roundWinnerId, evaluation.closeType(),
                    evaluation.closeBonus(), scoreList, true, matchWinnerId
            );
            runtime.markFinished(summary);
            return summary;
        }

        RoundSummary summary = new RoundSummary(
                finishedRoundNumber, roundWinnerId, evaluation.closeType(),
                evaluation.closeBonus(), scoreList, false, null
        );
        runtime.recordRoundSummary(summary);
        RoomSession refreshed = playerService.getRoom(roomCode);
        runtime.startNextRound(remainingActive, refreshed.config());
        return summary;
    }

    private String resolveMatchWinner(String roomCode, List<Player> remainingActive) {
        if (remainingActive.size() == 1) {
            return remainingActive.get(0).getPlayerId();
        }
        return playerService.getRoom(roomCode).players().stream()
                .min(Comparator.comparingInt(Player::getAccumulatedScore))
                .map(Player::getPlayerId)
                .orElseThrow(() -> new IllegalStateException("No hay jugadores registrados en la sala " + roomCode));
    }

    private PlayerGameView buildPlayerView(RoomSession room, RoomRuntime runtime, String viewerPlayerId) {
        List<OpponentView> opponentViews = buildOpponentViews(room.players(), runtime);
        IDeckService deck = runtime.getDeckService();
        String forbiddenDiscard = Objects.equals(viewerPlayerId, runtime.getCurrentTurnPlayerId())
                && runtime.getTurnPhase() == TurnPhase.MUST_DISCARD
                ? runtime.getForbiddenDiscardCardId()
                : null;
        return new PlayerGameView(
                room.matchId(),
                room.roomCode(),
                room.status(),
                room.config(),
                runtime.getRoundNumber(),
                runtime.getCurrentTurnPlayerId(),
                runtime.getTurnPhase(),
                deck.remainingDeckSize(),
                deck.peekDeckBack().orElse(null),
                deck.peekDiscard().orElse(null),
                viewerPlayerId,
                runtime.getHandCopy(viewerPlayerId),
                forbiddenDiscard,
                opponentViews,
                room.winnerId(),
                runtime.getLastRoundSummary()
        );
    }

    private SessionTicket toSessionTicket(JoinResult result) {
        Player player = result.player();
        return new SessionTicket(
                result.matchId(),
                result.roomCode(),
                player.getPlayerId(),
                player.getName(),
                player.getSessionToken(),
                player.getSeatIndex(),
                player.isHost(),
                result.config()
        );
    }

    private PlayerGameView buildLobbyView(RoomSession room, String viewerPlayerId) {
        return new PlayerGameView(
                room.matchId(), room.roomCode(), room.status(), room.config(),
                0, null, TurnPhase.WAITING_START, 0, null, null,
                viewerPlayerId, List.of(), null, buildLobbyOpponentViews(room.players()), room.winnerId(), null
        );
    }

    private List<OpponentView> buildLobbyOpponentViews(List<Player> players) {
        List<OpponentView> views = new ArrayList<>(players.size());
        for (Player player : players) {
            views.add(new OpponentView(
                    player.getPlayerId(), player.getName(), player.getSeatIndex(),
                    player.getAccumulatedScore(), player.getStatus(),
                    player.isConnected(), player.isHost(), 0, List.of()
            ));
        }
        return views;
    }

    private List<OpponentView> buildOpponentViews(List<Player> players, RoomRuntime runtime) {
        List<OpponentView> views = new ArrayList<>(players.size());
        for (Player player : players) {
            List<CardBack> backs = runtime.getHandBacks(player.getPlayerId());
            views.add(new OpponentView(
                    player.getPlayerId(), player.getName(), player.getSeatIndex(),
                    player.getAccumulatedScore(), player.getStatus(),
                    player.isConnected(), player.isHost(), backs.size(), backs
            ));
        }
        return views;
    }

    private void registerPlayersInScoreService(IScoreService scoreService, List<Player> players) {
        for (Player player : players) {
            scoreService.registerPlayer(player.getPlayerId());
        }
    }

    private RoomRuntime requireRuntime(String roomCode) {
        RoomRuntime runtime = activeRooms.get(roomCode);
        if (runtime == null) {
            throw new IllegalStateException("La partida en la sala " + roomCode + " aún no ha iniciado");
        }
        return runtime;
    }

    private void verifyTurnAndPhase(RoomRuntime runtime, String playerId, TurnPhase expectedPhase) {
        if (runtime.getTurnPhase() == TurnPhase.FINISHED) {
            throw new IllegalStateException("La partida ya ha finalizado");
        }
        if (!Objects.equals(runtime.getCurrentTurnPlayerId(), playerId)) {
            throw new IllegalStateException("No es el turno del jugador " + playerId);
        }
        if (runtime.getTurnPhase() != expectedPhase) {
            throw new IllegalStateException(
                    "Acción no permitida en fase " + runtime.getTurnPhase() + " (se esperaba " + expectedPhase + ")");
        }
    }

    private void verifyPlayerTurnForClose(RoomSession room, RoomRuntime runtime, String playerId) {
        if (room.status() != MatchStatus.EN_CURSO || runtime.getTurnPhase() == TurnPhase.FINISHED) {
            throw new IllegalStateException("La partida no está en curso");
        }
        if (!Objects.equals(runtime.getCurrentTurnPlayerId(), playerId)) {
            throw new IllegalStateException("Solo el jugador en turno puede declarar cierre");
        }
    }

    private void verifyHost(RoomSession room, String requesterPlayerId) {
        boolean isHost = room.players().stream()
                .anyMatch(p -> p.getPlayerId().equals(requesterPlayerId) && p.isHost());
        if (!isHost) {
            throw new IllegalStateException("Solo el anfitrión puede iniciar la partida");
        }
    }

    private void verifyMember(RoomSession room, String playerId) {
        boolean isMember = room.players().stream().anyMatch(p -> p.getPlayerId().equals(playerId));
        if (!isMember) {
            throw new IllegalArgumentException("El jugador " + playerId + " no pertenece a la sala " + room.roomCode());
        }
    }

    private static String normalizeCode(String roomCode) {
        Objects.requireNonNull(roomCode, "El código de sala no puede ser nulo");
        return roomCode.trim().toUpperCase(Locale.ROOT);
    }
}

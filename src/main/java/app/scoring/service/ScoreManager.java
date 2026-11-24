package app.scoring.service;

import app.core.Card;
import app.core.CardValuePolicy;
import app.core.EliminationLimit;
import app.core.GameConfig;
import app.scoring.calculator.PenaltyCalculator;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Administrador de puntajes acumulados y liquidación transaccional de rondas.
 * Solo depende de app.core, app.scoring.calculator y app.scoring.service (cumple RG-03).
 */
public final class ScoreManager implements IScoreService {

    private final PenaltyCalculator penaltyCalculator;
    private final IScoreRepository scoreRepository;
    private final Map<String, Integer> scores = new LinkedHashMap<>();
    private final Set<String> eliminatedPlayers = new HashSet<>();

    public ScoreManager() {
        this(new PenaltyCalculator(), null);
    }

    public ScoreManager(PenaltyCalculator penaltyCalculator) {
        this(penaltyCalculator, null);
    }

    public ScoreManager(IScoreRepository scoreRepository) {
        this(new PenaltyCalculator(), scoreRepository);
    }

    public ScoreManager(PenaltyCalculator penaltyCalculator, IScoreRepository scoreRepository) {
        this.penaltyCalculator = Objects.requireNonNull(
                penaltyCalculator, "El calculador de penalizaciones no puede ser nulo");
        this.scoreRepository = scoreRepository;
    }

    @Override
    public void registerPlayer(String playerId) {
        validatePlayerId(playerId);
        scores.putIfAbsent(playerId, 0);
    }

    @Override
    public int getScore(String playerId) {
        validatePlayerId(playerId);
        Integer score = scores.get(playerId);
        if (score == null) {
            throw new IllegalArgumentException("Jugador no registrado en ScoreManager: " + playerId);
        }
        return score;
    }

    @Override
    public Map<String, Integer> getAllScores() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(scores));
    }

    @Override
    public boolean isEliminated(String playerId) {
        validatePlayerId(playerId);
        return eliminatedPlayers.contains(playerId);
    }

    @Override
    public int calculatePenalty(List<Card> deadwoodCards, CardValuePolicy policy) {
        return penaltyCalculator.calculateDeadwoodPenalty(deadwoodCards, policy);
    }

    @Override
    public RoundScoreResult applyRoundDelta(String playerId, int roundDelta, EliminationLimit limit) {
        RoundScoreResult computed = computeProjectedResult(playerId, roundDelta, limit);
        commitInMemory(computed);
        return computed;
    }

    @Override
    public Map<String, RoundScoreResult> settleRound(
            String winnerPlayerId,
            int winnerBonus,
            Map<String, List<Card>> opponentDeadwood,
            GameConfig config
    ) {
        Map<String, RoundScoreResult> projected = computeRoundResults(
                winnerPlayerId, winnerBonus, opponentDeadwood, config);
        for (RoundScoreResult result : projected.values()) {
            commitInMemory(result);
        }
        return Collections.unmodifiableMap(projected);
    }

    @Override
    public Map<String, RoundScoreResult> settleAndPersistRound(
            String matchId,
            int roundNumber,
            String winnerPlayerId,
            String closeType,
            int winnerBonus,
            Map<String, List<Card>> opponentDeadwood,
            GameConfig config
    ) {
        Objects.requireNonNull(matchId, "El matchId no puede ser nulo");
        Objects.requireNonNull(closeType, "El tipo de cierre no puede ser nulo");
        if (roundNumber < 1) {
            throw new IllegalArgumentException("El número de ronda debe ser >= 1, recibido: " + roundNumber);
        }

        // 1. Calcular proyección sin mutar memoria todavía (preserva consistencia ante ROLLBACK)
        Map<String, RoundScoreResult> projected = computeRoundResults(
                winnerPlayerId, winnerBonus, opponentDeadwood, config);

        // 2. Persistir en SQLite dentro de transacción ACID (BEGIN / COMMIT / ROLLBACK)
        if (scoreRepository != null) {
            scoreRepository.persistRoundSettlement(
                    matchId, roundNumber, winnerPlayerId, closeType, winnerBonus, projected.values());
        }

        // 3. Confirmar en memoria solo si el COMMIT en BD fue exitoso
        for (RoundScoreResult result : projected.values()) {
            commitInMemory(result);
        }
        return Collections.unmodifiableMap(projected);
    }

    @Override
    public List<RoundHistoryEntry> getRoundHistory(String matchId) {
        Objects.requireNonNull(matchId, "El matchId no puede ser nulo");
        if (scoreRepository == null) {
            return List.of();
        }
        return scoreRepository.findRoundHistory(matchId);
    }

    @Override
    public void resetScores() {
        scores.clear();
        eliminatedPlayers.clear();
    }

    private Map<String, RoundScoreResult> computeRoundResults(
            String winnerPlayerId,
            int winnerBonus,
            Map<String, List<Card>> opponentDeadwood,
            GameConfig config
    ) {
        validatePlayerId(winnerPlayerId);
        Objects.requireNonNull(opponentDeadwood, "El mapa de sobrantes no puede ser nulo");
        Objects.requireNonNull(config, "La configuración no puede ser nula");

        EliminationLimit limit = config.getEliminationLimit();
        Map<String, RoundScoreResult> results = new LinkedHashMap<>();
        results.put(winnerPlayerId, computeProjectedResult(winnerPlayerId, winnerBonus, limit));

        for (Map.Entry<String, List<Card>> entry : opponentDeadwood.entrySet()) {
            String opponentId = entry.getKey();
            if (opponentId.equals(winnerPlayerId)) {
                continue;
            }
            int penalty = calculatePenalty(entry.getValue(), config.getCardValuePolicy());
            results.put(opponentId, computeProjectedResult(opponentId, penalty, limit));
        }
        return results;
    }

    private RoundScoreResult computeProjectedResult(
            String playerId,
            int roundDelta,
            EliminationLimit limit
    ) {
        validatePlayerId(playerId);
        Objects.requireNonNull(limit, "El límite de eliminación no puede ser nulo");

        int current = scores.getOrDefault(playerId, 0);
        int updated = current + roundDelta;
        boolean eliminated = limit.isEliminated(updated);
        return new RoundScoreResult(playerId, roundDelta, updated, eliminated);
    }

    private void commitInMemory(RoundScoreResult result) {
        scores.put(result.playerId(), result.totalScore());
        if (result.eliminated()) {
            eliminatedPlayers.add(result.playerId());
        } else {
            eliminatedPlayers.remove(result.playerId());
        }
    }

    private static void validatePlayerId(String playerId) {
        Objects.requireNonNull(playerId, "El playerId no puede ser nulo");
        if (playerId.isBlank()) {
            throw new IllegalArgumentException("El playerId no puede estar vacío");
        }
    }
}

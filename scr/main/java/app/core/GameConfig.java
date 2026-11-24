package app.core;

import java.util.Objects;

/**
 * Configuración mutable de una sala/partida.
 * Valida la consistencia de cantidad de jugadores (2-16, máximo 4 por baraja),
 * número de barajas (1-4) y el límite de cierre en Apuntado (sobrante estrictamente menor que N).
 */
public final class GameConfig {

    public static final int MIN_PLAYERS = 2;
    public static final int PLAYERS_PER_DECK = 4;
    public static final int MIN_DECKS = 1;
    public static final int MAX_DECKS = 4;
    public static final int MAX_PLAYERS = MAX_DECKS * PLAYERS_PER_DECK; // 16 jugadores (4 por baraja)
    public static final int HAND_SIZE = 10;

    public static final int DEFAULT_APUNTADO_CLOSE_LIMIT = 5;
    public static final int MIN_APUNTADO_CLOSE_LIMIT = 2;
    public static final int MAX_APUNTADO_CLOSE_LIMIT = 26;

    private GameMode gameMode;
    private int maxPlayers;
    private int deckCount;
    private boolean includeJokers;
    private CardValuePolicy cardValuePolicy;
    private CyclePolicy cyclePolicy;
    private EliminationLimit eliminationLimit;
    private int apuntadoCloseLimit;

    /**
     * Crea una configuración por defecto válida (Clásica, 4 jugadores, 2 barajas,
     * Jokers activos, valoración estándar, secuencias cíclicas, límite 101 y cierre Apuntado < 5).
     */
    public GameConfig() {
        this.gameMode = GameMode.CLASSIC;
        this.maxPlayers = 4;
        this.deckCount = 2;
        this.includeJokers = true;
        this.cardValuePolicy = CardValuePolicy.STANDARD;
        this.cyclePolicy = CyclePolicy.CYCLIC;
        this.eliminationLimit = EliminationLimit.LIMIT_101;
        this.apuntadoCloseLimit = DEFAULT_APUNTADO_CLOSE_LIMIT;
    }

    public GameConfig(
            GameMode gameMode,
            int maxPlayers,
            int deckCount,
            boolean includeJokers,
            CardValuePolicy cardValuePolicy,
            CyclePolicy cyclePolicy,
            EliminationLimit eliminationLimit
    ) {
        this(gameMode, maxPlayers, deckCount, includeJokers,
                cardValuePolicy, cyclePolicy, eliminationLimit, DEFAULT_APUNTADO_CLOSE_LIMIT);
    }

    public GameConfig(
            GameMode gameMode,
            int maxPlayers,
            int deckCount,
            boolean includeJokers,
            CardValuePolicy cardValuePolicy,
            CyclePolicy cyclePolicy,
            EliminationLimit eliminationLimit,
            int apuntadoCloseLimit
    ) {
        setGameMode(gameMode);
        setMaxPlayers(maxPlayers);
        setDeckCount(deckCount);
        setIncludeJokers(includeJokers);
        setCardValuePolicy(cardValuePolicy);
        setCyclePolicy(cyclePolicy);
        setEliminationLimit(eliminationLimit);
        setApuntadoCloseLimit(apuntadoCloseLimit);
        validate();
    }

    /**
     * Calcula el mínimo de barajas requeridas para soportar {@code playerCount} jugadores
     * a razón de máximo 4 jugadores por baraja:
     * - 2 a 4 jugadores: mínimo 1 baraja
     * - 5 a 8 jugadores: mínimo 2 barajas
     * - 9 a 12 jugadores: mínimo 3 barajas
     * - 13 a 16 jugadores: mínimo 4 barajas
     */
    public static int minDecksForPlayers(int playerCount) {
        validatePlayerRange(playerCount);
        return Math.max(MIN_DECKS, (playerCount + PLAYERS_PER_DECK - 1) / PLAYERS_PER_DECK);
    }

    public GameMode getGameMode() {
        return gameMode;
    }

    public void setGameMode(GameMode gameMode) {
        this.gameMode = Objects.requireNonNull(gameMode, "La modalidad de juego no puede ser nula");
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        validatePlayerRange(maxPlayers);
        this.maxPlayers = maxPlayers;
    }

    public int getDeckCount() {
        return deckCount;
    }

    public void setDeckCount(int deckCount) {
        validateDeckRange(deckCount);
        this.deckCount = deckCount;
    }

    public boolean isIncludeJokers() {
        return includeJokers;
    }

    public void setIncludeJokers(boolean includeJokers) {
        this.includeJokers = includeJokers;
    }

    public CardValuePolicy getCardValuePolicy() {
        return cardValuePolicy;
    }

    public void setCardValuePolicy(CardValuePolicy cardValuePolicy) {
        this.cardValuePolicy = Objects.requireNonNull(
                cardValuePolicy, "La política de valor de cartas no puede ser nula");
    }

    public CyclePolicy getCyclePolicy() {
        return cyclePolicy;
    }

    public void setCyclePolicy(CyclePolicy cyclePolicy) {
        this.cyclePolicy = Objects.requireNonNull(cyclePolicy, "La política de ciclos no puede ser nula");
    }

    public EliminationLimit getEliminationLimit() {
        return eliminationLimit;
    }

    public void setEliminationLimit(EliminationLimit eliminationLimit) {
        this.eliminationLimit = Objects.requireNonNull(
                eliminationLimit, "El límite de eliminación no puede ser nulo");
    }

    /**
     * Umbral exclusivo de cierre en Apuntado: permite cerrar si la carta sobrante vale
     * estrictamente menos que este valor (por defecto 5, es decir 0..4 puntos).
     */
    public int getApuntadoCloseLimit() {
        return apuntadoCloseLimit;
    }

    public void setApuntadoCloseLimit(int apuntadoCloseLimit) {
        validateApuntadoCloseLimit(apuntadoCloseLimit);
        this.apuntadoCloseLimit = apuntadoCloseLimit;
    }

    /**
     * Indica si la cantidad de barajas configurada es suficiente para {@code maxPlayers}.
     */
    public boolean isDeckCountSufficient() {
        return isDeckCountSufficientFor(maxPlayers);
    }

    /**
     * Indica si la cantidad de barajas configurada es suficiente para {@code playerCount} jugadores.
     */
    public boolean isDeckCountSufficientFor(int playerCount) {
        validatePlayerRange(playerCount);
        return deckCount >= minDecksForPlayers(playerCount);
    }

    /**
     * Valida todas las invariantes de configuración, incluyendo que el número de barajas
     * soporte adecuadamente a {@code maxPlayers} (máximo 4 jugadores por baraja).
     */
    public void validate() {
        validatePlayerRange(maxPlayers);
        validateDeckRange(deckCount);
        validateApuntadoCloseLimit(apuntadoCloseLimit);
        int requiredDecks = minDecksForPlayers(maxPlayers);
        if (deckCount < requiredDecks) {
            throw new IllegalArgumentException(
                    "Se requieren al menos " + requiredDecks + " baraja(s) para " + maxPlayers
                            + " jugadores (máximo " + PLAYERS_PER_DECK + " por baraja), pero se configuró: "
                            + deckCount);
        }
    }

    /**
     * Valida la configuración para un número concreto de jugadores activos al iniciar una partida.
     */
    public void validateForPlayers(int activePlayers) {
        validatePlayerRange(activePlayers);
        if (activePlayers > maxPlayers) {
            throw new IllegalArgumentException(
                    "La cantidad de jugadores activos (" + activePlayers
                            + ") supera el máximo configurado (" + maxPlayers + ")");
        }
        int requiredDecks = minDecksForPlayers(activePlayers);
        if (deckCount < requiredDecks) {
            throw new IllegalArgumentException(
                    "Se requieren al menos " + requiredDecks + " baraja(s) para " + activePlayers
                            + " jugadores activos, pero se configuró: " + deckCount);
        }
    }

    private static void validatePlayerRange(int playerCount) {
        if (playerCount < MIN_PLAYERS || playerCount > MAX_PLAYERS) {
            throw new IllegalArgumentException(
                    "La cantidad de jugadores debe estar entre " + MIN_PLAYERS
                            + " y " + MAX_PLAYERS + ", recibido: " + playerCount);
        }
    }

    private static void validateDeckRange(int deckCount) {
        if (deckCount < MIN_DECKS || deckCount > MAX_DECKS) {
            throw new IllegalArgumentException(
                    "La cantidad de barajas debe estar entre " + MIN_DECKS
                            + " y " + MAX_DECKS + ", recibido: " + deckCount);
        }
    }

    private static void validateApuntadoCloseLimit(int limit) {
        if (limit < MIN_APUNTADO_CLOSE_LIMIT || limit > MAX_APUNTADO_CLOSE_LIMIT) {
            throw new IllegalArgumentException(
                    "El límite de cierre de Apuntado debe estar entre " + MIN_APUNTADO_CLOSE_LIMIT
                            + " y " + MAX_APUNTADO_CLOSE_LIMIT + ", recibido: " + limit);
        }
    }
}

package app.rules.engine;

import app.core.Card;
import app.core.CyclePolicy;
import app.core.GameConfig;
import app.core.GameMode;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Motor de reglas desacoplado que despacha la evaluación hacia la estrategia
 * registrada para la modalidad concreta (CLASSIC o APUNTADO), soportando también
 * el modo MIXED mediante la elección de modalidad de cierre (RG-02, RG-04).
 */
public final class RuleEngine implements IRuleEngine {

    private final Map<GameMode, IGameRulesStrategy> strategies;
    private final MeldValidator meldValidator;
    private final HandPartitioner handPartitioner;

    public RuleEngine() {
        this(List.of(), new MeldValidator());
    }

    public RuleEngine(List<IGameRulesStrategy> strategies) {
        this(strategies, new MeldValidator());
    }

    public RuleEngine(List<IGameRulesStrategy> strategies, MeldValidator meldValidator) {
        Objects.requireNonNull(strategies, "La lista de estrategias no puede ser nula");
        this.meldValidator = Objects.requireNonNull(meldValidator, "El validador no puede ser nulo");
        this.handPartitioner = new HandPartitioner(this.meldValidator);
        EnumMap<GameMode, IGameRulesStrategy> map = new EnumMap<>(GameMode.class);
        for (IGameRulesStrategy strategy : strategies) {
            Objects.requireNonNull(strategy, "Ninguna estrategia puede ser nula");
            map.put(strategy.getMode(), strategy);
        }
        this.strategies = Map.copyOf(map);
    }

    @Override
    public CloseEvaluation evaluateClose(List<Card> hand, GameConfig config) {
        return evaluateClose(hand, config, null);
    }

    @Override
    public CloseEvaluation evaluateClose(List<Card> hand, GameConfig config, GameMode requestedCloseMode) {
        Objects.requireNonNull(hand, "La mano no puede ser nula");
        Objects.requireNonNull(config, "La configuración no puede ser nula");
        GameMode effectiveMode = config.getGameMode().resolveCloseMode(requestedCloseMode);
        return resolveStrategy(effectiveMode).evaluateClose(hand, config);
    }

    @Override
    public HandPartition evaluateHand(List<Card> hand, GameConfig config) {
        Objects.requireNonNull(hand, "La mano no puede ser nula");
        Objects.requireNonNull(config, "La configuración no puede ser nula");
        if (config.getGameMode() == GameMode.MIXED) {
            return handPartitioner.findBestPartition(hand, config);
        }
        return resolveStrategy(config.getGameMode()).evaluateHand(hand, config);
    }

    @Override
    public boolean isValidMeld(List<Card> cards, CyclePolicy cyclePolicy) {
        return meldValidator.isValidMeld(cards, cyclePolicy);
    }

    private IGameRulesStrategy resolveStrategy(GameMode mode) {
        IGameRulesStrategy strategy = strategies.get(mode);
        if (strategy == null) {
            throw new IllegalStateException("No hay estrategia registrada para la modalidad: " + mode);
        }
        return strategy;
    }
}

package app.rules.classic;

import app.core.Card;
import app.core.GameConfig;
import app.core.GameMode;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.HandPartition;
import app.rules.engine.HandPartitioner;
import app.rules.engine.IGameRulesStrategy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Estrategia de reglas para la modalidad Clásica.
 * Evalúa una cadena ordenada de evaluadores de mayor a menor bonificación (-25 a -10 pts)
 * manteniendo independencia total respecto a app.rules.apuntado (RG-02) y CC <= 10 (RG-04).
 */
public final class ClassicRulesStrategy implements IGameRulesStrategy {

    private final HandPartitioner partitioner;
    private final List<IClassicCloseEvaluator> evaluators;

    public ClassicRulesStrategy() {
        this(new HandPartitioner(), defaultEvaluators());
    }

    public ClassicRulesStrategy(HandPartitioner partitioner, List<IClassicCloseEvaluator> evaluators) {
        this.partitioner = Objects.requireNonNull(partitioner, "El particionador no puede ser nulo");
        this.evaluators = List.copyOf(Objects.requireNonNull(evaluators, "Los evaluadores no pueden ser nulos"));
    }

    public static List<IClassicCloseEvaluator> defaultEvaluators() {
        return List.of(
                new FourJokersEvaluator(),
                new FullStraightEvaluator(),
                new SevenThreeEvaluator(),
                new FourSixEvaluator(),
                new FourThreeThreeEvaluator()
        );
    }

    public String name() {
        return "classic";
    }

    @Override
    public GameMode getMode() {
        return GameMode.CLASSIC;
    }

    @Override
    public CloseEvaluation evaluateClose(List<Card> hand, GameConfig config) {
        Objects.requireNonNull(hand, "La mano no puede ser nula");
        Objects.requireNonNull(config, "La configuración no puede ser nula");

        for (IClassicCloseEvaluator evaluator : evaluators) {
            Optional<CloseEvaluation> match = evaluator.evaluate(hand, config, partitioner);
            if (match.isPresent()) {
                return match.get();
            }
        }
        return CloseEvaluation.invalid(partitioner.findBestPartition(hand, config));
    }

    @Override
    public HandPartition evaluateHand(List<Card> hand, GameConfig config) {
        return partitioner.findBestPartition(hand, config);
    }
}

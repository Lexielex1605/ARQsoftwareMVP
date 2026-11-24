package app.rules.classic;

import app.core.Card;
import app.core.GameConfig;
import app.rules.engine.CloseEvaluation;
import app.rules.engine.HandPartitioner;

import java.util.List;
import java.util.Optional;

/**
 * Evaluador individual de una condición de cierre en la modalidad Clásica.
 * Permite desacoplar cada regla de cierre con complejidad ciclomática <= 10 (RG-04).
 */
public interface IClassicCloseEvaluator {

    String getCloseType();

    int getBonusPoints();

    Optional<CloseEvaluation> evaluate(List<Card> hand, GameConfig config, HandPartitioner partitioner);
}

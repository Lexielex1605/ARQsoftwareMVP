package app.rules.engine;

import app.core.Card;
import app.core.CyclePolicy;
import app.core.GameConfig;
import app.core.GameMode;

import java.util.List;

/**
 * Fachada pública del módulo de reglas consumida por el mediador (GameOrchestrator).
 */
public interface IRuleEngine {

    CloseEvaluation evaluateClose(List<Card> hand, GameConfig config);

    /**
     * Evalúa el cierre permitiendo especificar la modalidad concreta (CLASSIC o APUNTADO),
     * obligatoria cuando la sala está configurada en modalidad MIXED.
     */
    CloseEvaluation evaluateClose(List<Card> hand, GameConfig config, GameMode requestedCloseMode);

    HandPartition evaluateHand(List<Card> hand, GameConfig config);

    boolean isValidMeld(List<Card> cards, CyclePolicy cyclePolicy);
}

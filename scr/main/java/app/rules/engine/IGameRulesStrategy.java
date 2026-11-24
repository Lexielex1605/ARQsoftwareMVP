package app.rules.engine;

import app.core.Card;
import app.core.GameConfig;
import app.core.GameMode;

import java.util.List;

/**
 * Contrato Strategy para las modalidades de juego (Clásica y Apuntado).
 * Permite independencia total entre estrategias según RG-02.
 */
public interface IGameRulesStrategy {

    GameMode getMode();

    /**
     * Evalúa si la mano cumple las reglas de cierre de esta modalidad.
     */
    CloseEvaluation evaluateClose(List<Card> hand, GameConfig config);

    /**
     * Calcula la mejor agrupación posible de la mano para minimizar sobrantes.
     */
    HandPartition evaluateHand(List<Card> hand, GameConfig config);
}

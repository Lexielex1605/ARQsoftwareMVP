package app.gameplay.controller;

import app.core.GameState;

public final class GameController {
    public String handle(GameState state) {
        return "turn:" + state.getId();
    }
}

package app.ui;

import app.core.GameState;

public final class GameView {
    public String render(GameState state) {
        return "view:" + state.getId();
    }
}

package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;

import java.util.Map;

public interface GameStrategy {
    DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params);
}

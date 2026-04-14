package de.neebs.franchise.control;

import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;

import java.util.List;
import java.util.Map;

/**
 * Extension of GameStrategy for strategies that learn from completed games.
 * FranchiseService calls onGameComplete after each headless game when the
 * strategy's name is listed in the request's learningModels field.
 */
public interface TrainableStrategy extends GameStrategy {
    void onGameComplete(List<GameState> trajectory, Map<PlayerColor, Integer> finalScores);
}

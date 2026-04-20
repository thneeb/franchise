package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Picks a uniformly random draw from the available moves.
 * Useful as a baseline opponent and to avoid the always-NOOP trap that
 * evaluation-based strategies can fall into at low search depths.
 */
@Component("RANDOM")
public class RandomStrategy implements GameStrategy {

    private final FranchiseService franchiseService;
    private final Random random = new Random();

    public RandomStrategy(@Lazy FranchiseService franchiseService) {
        this.franchiseService = franchiseService;
    }

    @Override
    public DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params) {
        List<DrawRecord> moves = franchiseService.getPossibleStrategyDrawsForState(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("No legal draws available for " + player);
        }
        return moves.get(random.nextInt(moves.size()));
    }
}

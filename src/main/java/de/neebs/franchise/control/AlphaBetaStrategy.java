package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Paranoid Alpha-Beta: the root player maximises their own influence; all other
 * players are treated as a single adversary that minimises it. This enables
 * standard two-player alpha-beta pruning while remaining applicable to 2-5
 * player games.
 */
@Component("AB_PRUNE")
public class AlphaBetaStrategy implements GameStrategy {

    private final FranchiseService franchiseService;
    private final CalibrationService calibrationService;

    public AlphaBetaStrategy(@Lazy FranchiseService franchiseService,
                              @Lazy CalibrationService calibrationService) {
        this.franchiseService = franchiseService;
        this.calibrationService = calibrationService;
    }

    @Override
    public DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params) {
        Map<String, Object> resolvedParams = MinimaxStrategy.resolveAutoParams(
                params, state.getPlayers().size(), calibrationService);
        int depth = MinimaxStrategy.parseDepth(resolvedParams);
        List<DrawRecord> moves = franchiseService.getPossibleDrawsForAI(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("No legal draws available for " + player);
        }

        DrawRecord best = null;
        int alpha = Integer.MIN_VALUE;
        for (DrawRecord move : moves) {
            GameState next = franchiseService.applyDrawOnState(state, move);
            int score = paranoid(next, depth - 1, alpha, Integer.MAX_VALUE, player, resolvedParams);
            if (score > alpha) {
                alpha = score;
                best = move;
            }
        }
        return best;
    }

    /**
     * Paranoid minimax with alpha-beta pruning.
     * The root player (maximizer) maximises; every other player minimises.
     */
    private int paranoid(GameState state, int depth, int alpha, int beta,
                         PlayerColor maximizer, Map<String, Object> params) {
        if (depth == 0 || state.isEnd()) {
            return MinimaxStrategy.evaluate(state, params).get(maximizer);
        }

        PlayerColor mover = state.getPlayers().get(state.getCurrentPlayerIndex());
        List<DrawRecord> moves = franchiseService.getPossibleDrawsForAI(state);
        if (moves.isEmpty()) {
            return MinimaxStrategy.evaluate(state, params).get(maximizer);
        }

        if (mover == maximizer) {
            int value = Integer.MIN_VALUE;
            for (DrawRecord move : moves) {
                GameState next = franchiseService.applyDrawOnState(state, move);
                value = Math.max(value, paranoid(next, depth - 1, alpha, beta, maximizer, params));
                alpha = Math.max(alpha, value);
                if (alpha >= beta) break; // beta cut-off
            }
            return value;
        } else {
            int value = Integer.MAX_VALUE;
            for (DrawRecord move : moves) {
                GameState next = franchiseService.applyDrawOnState(state, move);
                value = Math.min(value, paranoid(next, depth - 1, alpha, beta, maximizer, params));
                beta = Math.min(beta, value);
                if (alpha >= beta) break; // alpha cut-off
            }
            return value;
        }
    }
}

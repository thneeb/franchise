package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Opponent strategy that plays from a periodically-snapshotted ("frozen") copy of the Q network.
 * It never trains. Used as the stable opponent in Q_LEARNING self-play so the learner trains
 * against a fixed target rather than a simultaneously-updating one.
 *
 * The frozen snapshot is updated every {@link SelfPlayQStrategy#FROZEN_SYNC_INTERVAL} training
 * runs by the learner.
 */
@Component("Q_LEARNING_FROZEN")
public class FrozenQStrategy implements GameStrategy {

    private final FranchiseService franchiseService;
    private final SelfPlayQModelService modelService;
    private final StateEncoder encoder = new StateEncoder();

    public FrozenQStrategy(@Lazy FranchiseService franchiseService,
                           SelfPlayQModelService modelService) {
        this.franchiseService = franchiseService;
        this.modelService = modelService;
    }

    @Override
    public DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params) {
        List<DrawRecord> moves = franchiseService.getPossibleStrategyDrawsForState(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("No legal draws available for " + player);
        }
        QLearningTarget target = QLearningTarget.fromParams(params);
        String modelVariant = parseString(params, "modelVariant", null);
        NeuralNetwork network = modelService.getOrCreateFrozen(state.getPlayers().size(), target, modelVariant);
        DrawRecord best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (DrawRecord move : moves) {
            GameState next = franchiseService.applyDrawOnState(state, move);
            float score = network.predictClamped(encoder.encode(next, player));
            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best;
    }

    private static String parseString(Map<String, Object> params, String key, String defaultValue) {
        if (params == null) return defaultValue;
        Object value = params.get(key);
        if (value instanceof String s && !s.isBlank()) return s.trim();
        return defaultValue;
    }
}

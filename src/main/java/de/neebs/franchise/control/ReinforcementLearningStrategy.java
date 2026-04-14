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
 * Value-network strategy trained by Monte Carlo self-play.
 *
 * <p><b>Inference:</b> for every legal move, applies it to a copy of the state and evaluates
 * the resulting position with the neural network from the current player's perspective.
 * The move that yields the highest predicted final score is chosen.
 * With {@code epsilon > 0} a random move is taken instead (epsilon-greedy exploration).
 *
 * <p><b>Training:</b> {@link FranchiseService} calls {@link #onGameComplete} after each
 * headless game when this strategy's name appears in {@code PlayConfig.learningModels}.
 * For each non-initialisation turn in the trajectory we train
 * {@code V(nextState, mover) → mover's actual final influence / 50}.
 * The updated model is persisted after every game via {@link RlModelService}.
 *
 * <p><b>Params supported via {@code Map<String,Object>}:</b>
 * <ul>
 *   <li>{@code epsilon} (float, default 0.0) — exploration rate; automatically set to 0.3
 *       by the controller when this strategy is listed in {@code learningModels}</li>
 * </ul>
 */
@Component("REINFORCEMENT_LEARNING")
public class ReinforcementLearningStrategy implements TrainableStrategy {

    private static final float DEFAULT_LEARNING_RATE = 0.001f;
    private static final float SCORE_NORMALIZER = 50.0f;

    private final FranchiseService franchiseService;
    private final RlModelService modelService;
    private final StateEncoder encoder = new StateEncoder();
    private final Random random = new Random();

    public ReinforcementLearningStrategy(@Lazy FranchiseService franchiseService,
                                          RlModelService modelService) {
        this.franchiseService = franchiseService;
        this.modelService = modelService;
    }

    // -------------------------------------------------------------------------
    // GameStrategy
    // -------------------------------------------------------------------------

    @Override
    public DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params) {
        List<DrawRecord> moves = franchiseService.getPossibleDrawsForAI(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("No legal draws available for " + player);
        }

        float epsilon = parseFloat(params, "epsilon", 0.0f);
        if (epsilon > 0 && random.nextFloat() < epsilon) {
            return moves.get(random.nextInt(moves.size()));
        }

        NeuralNetwork network = modelService.getOrCreate(state.getPlayers().size());
        DrawRecord best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (DrawRecord move : moves) {
            GameState next = franchiseService.applyDrawOnState(state, move);
            float score = network.predict(encoder.encode(next, player));
            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best;
    }

    // -------------------------------------------------------------------------
    // TrainableStrategy
    // -------------------------------------------------------------------------

    /**
     * Trains the value network on the completed game trajectory.
     *
     * <p>For each non-initialisation turn {@code i} in the trajectory:
     * <pre>
     *   mover    = player whose turn it was at trajectory[i]
     *   nextState = trajectory[i + 1]   (state after mover's action)
     *   target   = finalScores[mover] / SCORE_NORMALIZER
     *   train:   V(encode(nextState, mover)) → target
     * </pre>
     * Synchronised to prevent concurrent training corruption when multiple
     * HTTP requests trigger parallel game loops.
     */
    @Override
    public synchronized void onGameComplete(List<GameState> trajectory,
                                             Map<PlayerColor, Integer> finalScores) {
        if (trajectory.size() < 2) return;
        int numPlayers = trajectory.get(0).getPlayers().size();
        NeuralNetwork network = modelService.getOrCreate(numPlayers);

        for (int i = 0; i < trajectory.size() - 1; i++) {
            GameState before = trajectory.get(i);
            if (before.isInitialization()) continue;
            PlayerColor mover = before.getPlayers().get(before.getCurrentPlayerIndex());
            Integer finalScore = finalScores.get(mover);
            if (finalScore == null) continue;
            GameState after = trajectory.get(i + 1);
            float target = finalScore / SCORE_NORMALIZER;
            network.train(encoder.encode(after, mover), target, DEFAULT_LEARNING_RATE);
        }

        modelService.save(network, numPlayers);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static float parseFloat(Map<String, Object> params, String key, float defaultValue) {
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v instanceof Number n) return n.floatValue();
        return defaultValue;
    }
}

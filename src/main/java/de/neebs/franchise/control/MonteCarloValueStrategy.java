package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import de.neebs.franchise.entity.Score;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Monte-Carlo value strategy trained by self-play.
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
 * The updated model is persisted after every game via {@link MonteCarloValueModelService}.
 *
 * <p><b>Params supported via {@code Map<String,Object>}:</b>
 * <ul>
 *   <li>{@code epsilon} (float, default 0.0) — exploration rate; automatically set to 0.3
 *       by the controller when this strategy is listed in {@code learningModels}</li>
 * </ul>
 */
@Component("MONTE_CARLO_VALUE")
public class MonteCarloValueStrategy implements TrainableStrategy {

    private static final float DEFAULT_LEARNING_RATE = 0.001f;

    private final FranchiseService franchiseService;
    private final MonteCarloValueModelService modelService;
    private final StateEncoder encoder = new StateEncoder();
    private final Random random = new Random();

    public MonteCarloValueStrategy(@Lazy FranchiseService franchiseService,
                                   MonteCarloValueModelService modelService) {
        this.franchiseService = franchiseService;
        this.modelService = modelService;
    }

    // -------------------------------------------------------------------------
    // GameStrategy
    // -------------------------------------------------------------------------

    @Override
    public DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params) {
        List<DrawRecord> moves = franchiseService.getPossibleDrawsForState(state);
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
     * <p>Only trains on turns where the mover is using the MONTE_CARLO_VALUE strategy
     * (i.e. skips opponent turns — training on random/other-strategy turns injects noise).
     *
     * <p>The training target blends two signals based on game progress
     * (measured by the region-track index, 0 = start, 1 = end):
     * <ul>
     *   <li><b>Early game</b>: relative income share ({@code myIncome / totalIncome}).
     *       Income is the leading indicator of future scoring because influence points are
     *       collected via income in each round.</li>
     *   <li><b>Late game</b>: relative influence share ({@code myInfluence / totalInfluence}).
     *       Once players have established income engines, raw influence position matters more.</li>
     * </ul>
     * Both signals are relative shares in [0,1], so a 2-player game produces 0.5 when tied.
     *
     * Synchronised to prevent concurrent training corruption when multiple
     * HTTP requests trigger parallel game loops.
     */
    @Override
    public synchronized TrainingTimings onGameComplete(List<GameState> trajectory,
                                                       Map<PlayerColor, Integer> finalScores,
                                                       Map<PlayerColor, String> playerStrategies,
                                                       Map<PlayerColor, Map<String, Object>> playerParams) {
        if (trajectory.size() < 2) return TrainingTimings.ZERO;
        int numPlayers = trajectory.get(0).getPlayers().size();
        NeuralNetwork network = modelService.getOrCreate(numPlayers);
        long trainingStart = System.nanoTime();
        boolean trained = false;

        for (int i = 0; i < trajectory.size() - 1; i++) {
            GameState before = trajectory.get(i);
            if (before.isInitialization()) continue;
            PlayerColor mover = before.getPlayers().get(before.getCurrentPlayerIndex());
            // Only train on turns where this strategy was in control
            if (!"MONTE_CARLO_VALUE".equals(playerStrategies.get(mover))) continue;

            GameState after = trajectory.get(i + 1);

            // Game progress: 0.0 = beginning, 1.0 = end of game
            float progress = Math.min(1.0f, after.getRegionTrackIndex() / 10.0f);

            // Income-relative share (early-game signal: who is building faster?)
            int myIncome = after.getScores().get(mover).getIncome();
            int totalIncome = after.getScores().values().stream().mapToInt(Score::getIncome).sum();
            float incomeTarget = totalIncome > 0
                    ? (float) myIncome / totalIncome
                    : 1.0f / numPlayers;

            // Influence-relative share (late-game signal: who is ahead on the board?)
            int myInfluence = after.getScores().get(mover).getInfluence();
            int totalInfluence = after.getScores().values().stream().mapToInt(Score::getInfluence).sum();
            float influenceTarget = totalInfluence > 0
                    ? (float) myInfluence / totalInfluence
                    : 1.0f / numPlayers;

            float target = (1.0f - progress) * incomeTarget + progress * influenceTarget;

            network.train(encoder.encode(after, mover), target, DEFAULT_LEARNING_RATE);
            trained = true;
        }

        if (!trained) return TrainingTimings.ZERO;
        long trainingNanos = System.nanoTime() - trainingStart;
        network.setTrainingRuns(network.getTrainingRuns() + 1);
        long saveStart = System.nanoTime();
        modelService.save(network, numPlayers);
        return new TrainingTimings(trainingNanos, System.nanoTime() - saveStart);
    }

    @Override
    public long getTrainingRuns(int numPlayers) {
        return modelService.getTrainingRuns(numPlayers);
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

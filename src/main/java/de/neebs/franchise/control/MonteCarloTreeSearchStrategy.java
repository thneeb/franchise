package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Outcome-trained value network combined with Monte Carlo tree search.
 *
 * <p>Search uses MCTS with a value network leaf evaluation. Each node stores
 * per-player value estimates so every mover can optimise from their own
 * perspective in multi-player games. Training uses terminal rewards only:
 * win = 1.0, tie = 0.5, loss = 0.0.
 */
@Component("MONTE_CARLO_TREE_SEARCH")
public class MonteCarloTreeSearchStrategy implements TrainableStrategy {

    private static final String STRATEGY_NAME = "MONTE_CARLO_TREE_SEARCH";
    private static final int DEFAULT_SIMULATIONS = 96;
    private static final double DEFAULT_EXPLORATION = 1.4;
    private static final float DEFAULT_LEARNING_RATE = 0.001f;
    private static final int DEFAULT_BATCH_SIZE = 64;
    private static final int DEFAULT_UPDATES_PER_GAME = 8;
    private static final int DEFAULT_REPLAY_CAPACITY = 20_000;

    private final MonteCarloTreeSearchModelService modelService;
    private final FranchiseService franchiseService;
    private final StateEncoder encoder = new StateEncoder();
    private final ReplayBuffer<ValueTrainingSample> replayBuffer =
            new ReplayBuffer<>(DEFAULT_REPLAY_CAPACITY);
    private final Random random = new Random();

    public MonteCarloTreeSearchStrategy(MonteCarloTreeSearchModelService modelService,
                                        @Lazy FranchiseService franchiseService) {
        this.modelService = modelService;
        this.franchiseService = franchiseService;
    }

    @Override
    public DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params) {
        List<DrawRecord> moves = franchiseService.getPossibleDrawsForState(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("No legal draws available for " + player);
        }

        float epsilon = parseFloat(params, "epsilon", 0.0f);
        if (epsilon > 0.0f && random.nextFloat() < epsilon) {
            return moves.get(random.nextInt(moves.size()));
        }

        NeuralNetwork network = modelService.getOrCreate(state.getPlayers().size());
        int simulations = parseInt(params, "simulations", DEFAULT_SIMULATIONS);
        double exploration = parseDouble(params, "exploration", DEFAULT_EXPLORATION);
        MonteCarloTreeSearch search = new MonteCarloTreeSearch(franchiseService, network);
        return search.selectMove(state, simulations, exploration);
    }

    @Override
    public synchronized TrainingTimings onGameComplete(List<GameState> trajectory,
                                                       Map<PlayerColor, Integer> finalScores,
                                                       Map<PlayerColor, String> playerStrategies,
                                                       Map<PlayerColor, Map<String, Object>> playerParams) {
        if (trajectory.size() < 2) return TrainingTimings.ZERO;

        int numPlayers = trajectory.get(0).getPlayers().size();
        NeuralNetwork network = modelService.getOrCreate(numPlayers);
        long trainingStart = System.nanoTime();

        List<ValueTrainingSample> freshSamples = new ArrayList<>();
        for (int i = 0; i < trajectory.size() - 1; i++) {
            GameState before = trajectory.get(i);
            PlayerColor mover = before.getPlayers().get(before.getCurrentPlayerIndex());
            if (!STRATEGY_NAME.equals(playerStrategies.get(mover))) continue;
            freshSamples.add(new ValueTrainingSample(
                    encoder.encode(before, mover),
                    outcomeFor(mover, finalScores)));
        }

        if (freshSamples.isEmpty()) return TrainingTimings.ZERO;

        replayBuffer.addAll(freshSamples);
        for (int i = 0; i < DEFAULT_UPDATES_PER_GAME; i++) {
            List<ValueTrainingSample> batch = replayBuffer.sample(DEFAULT_BATCH_SIZE);
            network.trainBatch(batch, DEFAULT_LEARNING_RATE);
        }
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

    static float outcomeFor(PlayerColor player, Map<PlayerColor, Integer> finalScores) {
        int best = finalScores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        long winners = finalScores.values().stream().filter(score -> score == best).count();
        if (finalScores.getOrDefault(player, Integer.MIN_VALUE) != best) {
            return 0.0f;
        }
        return winners == 1 ? 1.0f : 0.5f;
    }

    private static int parseInt(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) return defaultValue;
        Object value = params.get(key);
        if (value instanceof Number number) return number.intValue();
        return defaultValue;
    }

    private static float parseFloat(Map<String, Object> params, String key, float defaultValue) {
        if (params == null) return defaultValue;
        Object value = params.get(key);
        if (value instanceof Number number) return number.floatValue();
        return defaultValue;
    }

    private static double parseDouble(Map<String, Object> params, String key, double defaultValue) {
        if (params == null) return defaultValue;
        Object value = params.get(key);
        if (value instanceof Number number) return number.doubleValue();
        return defaultValue;
    }
}

package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import de.neebs.franchise.entity.TrainingRunCount;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Q-learning strategy that estimates the long-term value of the state produced by a move.
 */
@Component("Q_LEARNING")
public class SelfPlayQStrategy implements TrainableStrategy {

    private static final String STRATEGY_NAME = "Q_LEARNING";
    private static final float DEFAULT_LEARNING_RATE = 0.001f;
    private static final int DEFAULT_BATCH_SIZE = 64;
    private static final int DEFAULT_UPDATES_PER_GAME = 8;
    private static final int DEFAULT_REPLAY_CAPACITY = 20_000;
    private static final float DISCOUNT = 0.99f;

    private final FranchiseService franchiseService;
    private final SelfPlayQModelService modelService;
    private final StateEncoder encoder = new StateEncoder();
    private final Map<QLearningTarget, ReplayBuffer<ValueTrainingSample>> replayBuffers =
            java.util.Arrays.stream(QLearningTarget.values()).collect(Collectors.toMap(
                    target -> target,
                    ignored -> new ReplayBuffer<>(DEFAULT_REPLAY_CAPACITY),
                    (left, right) -> left,
                    () -> new EnumMap<>(QLearningTarget.class)));
    private final Random random = new Random();

    public SelfPlayQStrategy(@Lazy FranchiseService franchiseService,
                             SelfPlayQModelService modelService) {
        this.franchiseService = franchiseService;
        this.modelService = modelService;
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

        QLearningTarget trainingTarget = QLearningTarget.fromParams(params);
        NeuralNetwork network = modelService.getOrCreate(state.getPlayers().size(), trainingTarget);
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

    @Override
    public synchronized TrainingTimings onGameComplete(List<GameState> trajectory,
                                                       Map<PlayerColor, Integer> finalScores,
                                                       Map<PlayerColor, String> playerStrategies,
                                                       Map<PlayerColor, Map<String, Object>> playerParams) {
        if (trajectory.size() < 2) return TrainingTimings.ZERO;

        int numPlayers = trajectory.get(0).getPlayers().size();
        long trainingStart = System.nanoTime();
        long modelSaveNanos = 0L;
        Set<QLearningTarget> activeTargets = activeTargets(playerStrategies, playerParams);
        boolean trained = false;

        for (QLearningTarget trainingTarget : activeTargets) {
            NeuralNetwork network = modelService.getOrCreate(numPlayers, trainingTarget);
            ReplayBuffer<ValueTrainingSample> replayBuffer = replayBuffers.get(trainingTarget);
            List<ValueTrainingSample> freshSamples = buildSamples(
                    trajectory,
                    finalScores,
                    playerStrategies,
                    playerParams,
                    trainingTarget);
            if (freshSamples.isEmpty()) {
                continue;
            }

            replayBuffer.addAll(freshSamples);
            for (int i = 0; i < DEFAULT_UPDATES_PER_GAME; i++) {
                List<ValueTrainingSample> batch = replayBuffer.sample(DEFAULT_BATCH_SIZE);
                network.trainBatch(batch, DEFAULT_LEARNING_RATE);
            }
            network.setTrainingRuns(network.getTrainingRuns() + 1);
            long saveStart = System.nanoTime();
            modelService.save(network, numPlayers, trainingTarget);
            modelSaveNanos += System.nanoTime() - saveStart;
            trained = true;
        }
        if (!trained) return TrainingTimings.ZERO;
        long elapsedNanos = System.nanoTime() - trainingStart;
        return new TrainingTimings(Math.max(0L, elapsedNanos - modelSaveNanos), modelSaveNanos);
    }

    @Override
    public long getTrainingRuns(int numPlayers) {
        long terminalRuns = modelService.getTrainingRuns(numPlayers, QLearningTarget.TERMINAL_OUTCOME);
        long influenceRuns = modelService.getTrainingRuns(numPlayers, QLearningTarget.INFLUENCE);
        return Math.max(terminalRuns, influenceRuns);
    }

    @Override
    public List<TrainingRunCount> getTrainingRunCounts(int numPlayers,
                                                       Map<PlayerColor, String> playerStrategies,
                                                       Map<PlayerColor, Map<String, Object>> playerParams,
                                                       String strategyName) {
        return activeTargets(playerStrategies, playerParams).stream()
                .map(trainingTarget -> new TrainingRunCount(
                        strategyName,
                        trainingTarget.name(),
                        modelService.getTrainingRuns(numPlayers, trainingTarget)))
                .toList();
    }

    static float outcomeFor(PlayerColor player, Map<PlayerColor, Integer> finalScores) {
        int best = finalScores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        long winners = finalScores.values().stream().filter(score -> score == best).count();
        if (finalScores.getOrDefault(player, Integer.MIN_VALUE) != best) {
            return 0.0f;
        }
        return winners == 1 ? 1.0f : 0.5f;
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    private static float parseFloat(Map<String, Object> params, String key, float defaultValue) {
        if (params == null) return defaultValue;
        Object value = params.get(key);
        if (value instanceof Number number) return number.floatValue();
        return defaultValue;
    }

    private List<ValueTrainingSample> buildSamples(List<GameState> trajectory,
                                                   Map<PlayerColor, Integer> finalScores,
                                                   Map<PlayerColor, String> playerStrategies,
                                                   Map<PlayerColor, Map<String, Object>> playerParams,
                                                   QLearningTarget trainingTarget) {
        Map<PlayerColor, Float> futureTargets = initialFutureTargets(trajectory, finalScores, trainingTarget);
        List<ValueTrainingSample> freshSamples = new ArrayList<>();

        for (int i = trajectory.size() - 2; i >= 0; i--) {
            GameState before = trajectory.get(i);
            GameState after = trajectory.get(i + 1);
            PlayerColor mover = before.getPlayers().get(before.getCurrentPlayerIndex());
            float target = switch (trainingTarget) {
                case TERMINAL_OUTCOME -> clamp01(
                        DISCOUNT * futureTargets.getOrDefault(mover, outcomeFor(mover, finalScores)));
                case INFLUENCE -> clamp01(
                        influenceReward(before, after, mover, finalScores)
                                + DISCOUNT * futureTargets.getOrDefault(mover, 0.0f));
            };

            if (STRATEGY_NAME.equals(playerStrategies.get(mover))
                    && QLearningTarget.fromParams(playerParams.get(mover)) == trainingTarget) {
                freshSamples.add(new ValueTrainingSample(encoder.encode(after, mover), target));
            }
            futureTargets.put(mover, target);
        }
        return freshSamples;
    }

    private Map<PlayerColor, Float> initialFutureTargets(List<GameState> trajectory,
                                                         Map<PlayerColor, Integer> finalScores,
                                                         QLearningTarget trainingTarget) {
        Map<PlayerColor, Float> futureTargets = new EnumMap<>(PlayerColor.class);
        for (PlayerColor player : trajectory.get(0).getPlayers()) {
            futureTargets.put(player, switch (trainingTarget) {
                case TERMINAL_OUTCOME -> outcomeFor(player, finalScores);
                case INFLUENCE -> finalInfluenceShare(player, finalScores);
            });
        }
        return futureTargets;
    }

    private float influenceReward(GameState before,
                                  GameState after,
                                  PlayerColor mover,
                                  Map<PlayerColor, Integer> finalScores) {
        int beforeInfluence = before.getScores().get(mover).getInfluence();
        int afterInfluence = after.getScores().get(mover).getInfluence();
        int totalFinalInfluence = Math.max(1, finalScores.values().stream().mapToInt(Integer::intValue).sum());
        return Math.max(0.0f, (afterInfluence - beforeInfluence) / (float) totalFinalInfluence);
    }

    private float finalInfluenceShare(PlayerColor player, Map<PlayerColor, Integer> finalScores) {
        int totalFinalInfluence = Math.max(1, finalScores.values().stream().mapToInt(Integer::intValue).sum());
        return finalScores.getOrDefault(player, 0) / (float) totalFinalInfluence;
    }

    private Set<QLearningTarget> activeTargets(Map<PlayerColor, String> playerStrategies,
                                               Map<PlayerColor, Map<String, Object>> playerParams) {
        Set<QLearningTarget> activeTargets = new LinkedHashSet<>();
        for (Map.Entry<PlayerColor, String> entry : playerStrategies.entrySet()) {
            if (!STRATEGY_NAME.equals(entry.getValue())) {
                continue;
            }
            activeTargets.add(QLearningTarget.fromParams(playerParams.get(entry.getKey())));
        }
        return activeTargets;
    }
}

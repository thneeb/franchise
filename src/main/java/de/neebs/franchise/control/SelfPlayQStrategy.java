package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import de.neebs.franchise.entity.Score;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Self-play learner that estimates the long-term value of the state produced by a move.
 */
@Component("SELF_PLAY_Q")
public class SelfPlayQStrategy implements TrainableStrategy {

    private static final String STRATEGY_NAME = "SELF_PLAY_Q";
    private static final float DEFAULT_LEARNING_RATE = 0.001f;
    private static final int DEFAULT_BATCH_SIZE = 64;
    private static final int DEFAULT_UPDATES_PER_GAME = 8;
    private static final int DEFAULT_REPLAY_CAPACITY = 20_000;
    private static final float DISCOUNT = 0.92f;

    private final FranchiseService franchiseService;
    private final SelfPlayQModelService modelService;
    private final StateEncoder encoder = new StateEncoder();
    private final ReplayBuffer<ValueTrainingSample> replayBuffer =
            new ReplayBuffer<>(DEFAULT_REPLAY_CAPACITY);
    private final Random random = new Random();

    public SelfPlayQStrategy(@Lazy FranchiseService franchiseService,
                             SelfPlayQModelService modelService) {
        this.franchiseService = franchiseService;
        this.modelService = modelService;
    }

    @Override
    public DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params) {
        List<DrawRecord> moves = franchiseService.getPossibleDrawsForAI(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("No legal draws available for " + player);
        }

        float epsilon = parseFloat(params, "epsilon", 0.0f);
        if (epsilon > 0.0f && random.nextFloat() < epsilon) {
            return moves.get(random.nextInt(moves.size()));
        }

        NeuralNetwork network = modelService.getOrCreate(state.getPlayers().size());
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
    public synchronized void onGameComplete(List<GameState> trajectory,
                                            Map<PlayerColor, Integer> finalScores,
                                            Map<PlayerColor, String> playerStrategies) {
        if (trajectory.size() < 2) return;

        int numPlayers = trajectory.get(0).getPlayers().size();
        NeuralNetwork network = modelService.getOrCreate(numPlayers);

        Map<PlayerColor, Float> futureTargets = new EnumMap<>(PlayerColor.class);
        for (PlayerColor player : trajectory.get(0).getPlayers()) {
            futureTargets.put(player, outcomeFor(player, finalScores));
        }

        List<ValueTrainingSample> freshSamples = new ArrayList<>();
        for (int i = trajectory.size() - 2; i >= 0; i--) {
            GameState before = trajectory.get(i);
            GameState after = trajectory.get(i + 1);
            PlayerColor mover = before.getPlayers().get(before.getCurrentPlayerIndex());

            float target = clamp01(immediateReward(before, after, mover)
                    + DISCOUNT * futureTargets.getOrDefault(mover, outcomeFor(mover, finalScores)));

            if (STRATEGY_NAME.equals(playerStrategies.get(mover))) {
                freshSamples.add(new ValueTrainingSample(encoder.encode(after, mover), target));
            }
            futureTargets.put(mover, target);
        }

        if (freshSamples.isEmpty()) return;

        replayBuffer.addAll(freshSamples);
        for (int i = 0; i < DEFAULT_UPDATES_PER_GAME; i++) {
            List<ValueTrainingSample> batch = replayBuffer.sample(DEFAULT_BATCH_SIZE);
            network.trainBatch(batch, DEFAULT_LEARNING_RATE);
        }

        modelService.save(network, numPlayers);
    }

    static float outcomeFor(PlayerColor player, Map<PlayerColor, Integer> finalScores) {
        int best = finalScores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        long winners = finalScores.values().stream().filter(score -> score == best).count();
        if (finalScores.getOrDefault(player, Integer.MIN_VALUE) != best) {
            return 0.0f;
        }
        return winners == 1 ? 1.0f : 0.5f;
    }

    private static float immediateReward(GameState before, GameState after, PlayerColor mover) {
        Score beforeScore = before.getScores().get(mover);
        Score afterScore = after.getScores().get(mover);

        float influenceGain = Math.max(0, afterScore.getInfluence() - beforeScore.getInfluence()) / 50.0f;
        float incomeGain = Math.max(0, afterScore.getIncome() - beforeScore.getIncome()) / 20.0f;
        return 0.75f * influenceGain + 0.25f * incomeGain;
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
}

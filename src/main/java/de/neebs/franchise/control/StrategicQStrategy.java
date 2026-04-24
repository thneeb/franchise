package de.neebs.franchise.control;

import de.neebs.franchise.entity.BonusTileUsage;
import de.neebs.franchise.entity.City;
import de.neebs.franchise.entity.Connection;
import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rule-filtered MINIMAX depth-1 agent. Domain rules constrain the move pool;
 * MINIMAX evaluates the resulting opponent-response states.
 */
@Component("STRATEGIC_Q")
public class StrategicQStrategy implements GameStrategy {

    private final FranchiseService franchiseService;
    private final SelfPlayQModelService modelService;
    private final StateEncoder encoder = new StateEncoder();
    private final List<StrategyRule> rules = List.of(
            new UseExtensionBonusTileEarlyRule(),
            new AvoidIncreaseInSafeCityRule()
    );

    public StrategicQStrategy(@Lazy FranchiseService franchiseService,
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

        List<DrawRecord> candidates = applyRules(moves, state, player);

        QLearningTarget target = QLearningTarget.fromParams(params);
        NeuralNetwork network = modelService.getOrCreate(state.getPlayers().size(), target);

        DrawRecord best = null;
        float bestScore = Float.NEGATIVE_INFINITY;
        for (DrawRecord move : candidates) {
            GameState next = franchiseService.applyDrawOnState(state, move);
            float score = network.predictClamped(encoder.encode(next, player));
            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best;
    }

    private List<DrawRecord> applyRules(List<DrawRecord> moves, GameState state, PlayerColor player) {
        List<DrawRecord> candidates = moves;
        for (StrategyRule rule : rules) {
            candidates = rule.filter(candidates, state, player);
        }
        return candidates;
    }

    // -------------------------------------------------------------------------
    // Rule: use the EXTENSION bonus tile in the first N rounds whenever possible
    // -------------------------------------------------------------------------

    private static final class UseExtensionBonusTileEarlyRule implements StrategyRule {
        private static final int EARLY_ROUNDS = 5;

        @Override
        public List<DrawRecord> filter(List<DrawRecord> moves, GameState state, PlayerColor player) {
            if (state.getRound() > EARLY_ROUNDS) return moves;
            if (state.getScores().get(player).getBonusTiles() <= 0) return moves;

            List<DrawRecord> withExtension = moves.stream()
                    .filter(m -> m.getBonusTileUsage() == BonusTileUsage.EXTENSION)
                    .collect(Collectors.toList());
            return withExtension.isEmpty() ? moves : withExtension;
        }
    }

    // -------------------------------------------------------------------------
    // Rule: don't spend $1 increasing in a city that no opponent can enter
    // -------------------------------------------------------------------------

    private static final class AvoidIncreaseInSafeCityRule implements StrategyRule {
        private static final Map<City, Set<City>> ADJACENCY = buildAdjacency();

        private static Map<City, Set<City>> buildAdjacency() {
            Map<City, Set<City>> map = new HashMap<>();
            for (Connection conn : Rules.CONNECTIONS) {
                List<City> pair = List.copyOf(conn.cities());
                for (City a : pair) {
                    for (City b : pair) {
                        if (a != b) {
                            map.computeIfAbsent(a, k -> EnumSet.noneOf(City.class)).add(b);
                        }
                    }
                }
            }
            // Make each entry unmodifiable but keep the outer map mutable for computeIfAbsent above;
            // wrap the whole thing once done.
            Map<City, Set<City>> result = new EnumMap<>(City.class);
            map.forEach((city, neighbors) -> result.put(city, Collections.unmodifiableSet(neighbors)));
            return Collections.unmodifiableMap(result);
        }

        @Override
        public List<DrawRecord> filter(List<DrawRecord> moves, GameState state, PlayerColor player) {
            List<DrawRecord> filtered = moves.stream()
                    .filter(move -> !isWastefulIncreaseOnly(move, state, player))
                    .collect(Collectors.toList());
            return filtered.isEmpty() ? moves : filtered;
        }

        /**
         * A move is "wasteful" if it has no extension and every increase city is safe
         * (no opponent has a branch in any directly adjacent city or town).
         */
        private boolean isWastefulIncreaseOnly(DrawRecord move, GameState state, PlayerColor player) {
            if (!move.getExtension().isEmpty()) return false;
            if (move.getIncrease().isEmpty()) return false;
            return move.getIncrease().stream().allMatch(city -> isSafeForPlayer(city, state, player));
        }

        private boolean isSafeForPlayer(City city, GameState state, PlayerColor player) {
            for (City neighbor : ADJACENCY.getOrDefault(city, Set.of())) {
                PlayerColor[] slots = state.getCityBranches().get(neighbor);
                if (slots == null) continue;
                for (PlayerColor slot : slots) {
                    if (slot != null && slot != player) return false;
                }
            }
            return true;
        }
    }

}

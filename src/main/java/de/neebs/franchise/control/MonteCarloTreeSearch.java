package de.neebs.franchise.control;

import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

class MonteCarloTreeSearch {

    private final FranchiseService franchiseService;
    private final NeuralNetwork network;
    private final StateEncoder encoder = new StateEncoder();
    private final Random random = new Random();

    MonteCarloTreeSearch(FranchiseService franchiseService, NeuralNetwork network) {
        this.franchiseService = franchiseService;
        this.network = network;
    }

    DrawRecord selectMove(GameState rootState, int simulations, double explorationConstant) {
        SearchNode root = new SearchNode(rootState, null);
        for (int i = 0; i < Math.max(1, simulations); i++) {
            runSimulation(root, explorationConstant);
        }
        PlayerColor rootPlayer = rootState.getPlayers().get(rootState.getCurrentPlayerIndex());
        return root.bestMove(rootPlayer);
    }

    private void runSimulation(SearchNode root, double explorationConstant) {
        List<SearchNode> path = new ArrayList<>();
        SearchNode node = root;
        path.add(node);

        while (node.isExpanded()
                && !node.isTerminal()
                && !node.hasUnvisitedChildren()
                && !node.children().isEmpty()) {
            node = node.selectChild(explorationConstant);
            path.add(node);
        }

        if (!node.isTerminal()) {
            if (!node.isExpanded()) {
                node.expand(franchiseService);
            }
            SearchNode child = node.randomUnvisitedChild(random);
            if (child != null) {
                node = child;
                path.add(node);
            }
        }

        Map<PlayerColor, Double> values = evaluate(node.state());
        for (SearchNode step : path) {
            step.backpropagate(values);
        }
    }

    private Map<PlayerColor, Double> evaluate(GameState state) {
        if (state.isEnd()) {
            return terminalValues(state);
        }
        Map<PlayerColor, Double> values = new EnumMap<>(PlayerColor.class);
        for (PlayerColor player : state.getPlayers()) {
            values.put(player, (double) network.predictClamped(encoder.encode(state, player)));
        }
        return values;
    }

    private static Map<PlayerColor, Double> terminalValues(GameState state) {
        Map<PlayerColor, Integer> finalScores = new EnumMap<>(PlayerColor.class);
        state.getScores().forEach((player, score) -> finalScores.put(player, score.getInfluence()));
        Map<PlayerColor, Double> values = new EnumMap<>(PlayerColor.class);
        for (PlayerColor player : state.getPlayers()) {
            values.put(player, (double) MonteCarloTreeSearchStrategy.outcomeFor(player, finalScores));
        }
        return values;
    }

    static final class SearchNode {
        private final GameState state;
        private final DrawRecord moveFromParent;
        private final EnumMap<PlayerColor, Double> valueSums = new EnumMap<>(PlayerColor.class);
        private final List<SearchNode> children = new ArrayList<>();
        private int visits;
        private boolean expanded;

        SearchNode(GameState state, DrawRecord moveFromParent) {
            this.state = state;
            this.moveFromParent = moveFromParent;
            for (PlayerColor player : state.getPlayers()) {
                valueSums.put(player, 0.0);
            }
        }

        GameState state() {
            return state;
        }

        List<SearchNode> children() {
            return children;
        }

        boolean isTerminal() {
            return state.isEnd();
        }

        boolean isExpanded() {
            return expanded;
        }

        boolean hasUnvisitedChildren() {
            return children.stream().anyMatch(child -> child.visits == 0);
        }

        void expand(FranchiseService franchiseService) {
            if (expanded || isTerminal()) return;
            for (DrawRecord move : franchiseService.getPossibleDrawsForState(state)) {
                GameState next = franchiseService.applyDrawOnState(state, move);
                children.add(new SearchNode(next, move));
            }
            expanded = true;
        }

        SearchNode randomUnvisitedChild(Random random) {
            List<SearchNode> unvisited = children.stream()
                    .filter(child -> child.visits == 0)
                    .toList();
            if (unvisited.isEmpty()) return null;
            return unvisited.get(random.nextInt(unvisited.size()));
        }

        SearchNode selectChild(double explorationConstant) {
            PlayerColor mover = state.getPlayers().get(state.getCurrentPlayerIndex());
            SearchNode best = null;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (SearchNode child : children) {
                double exploitation = child.meanValue(mover);
                double exploration = explorationConstant
                        * Math.sqrt(Math.log(Math.max(1, visits)) / child.visits);
                double score = exploitation + exploration;
                if (score > bestScore) {
                    bestScore = score;
                    best = child;
                }
            }
            return best;
        }

        void backpropagate(Map<PlayerColor, Double> values) {
            visits++;
            for (Map.Entry<PlayerColor, Double> entry : values.entrySet()) {
                valueSums.merge(entry.getKey(), entry.getValue(), Double::sum);
            }
        }

        double meanValue(PlayerColor player) {
            if (visits == 0) return 0.5;
            return valueSums.getOrDefault(player, 0.0) / visits;
        }

        DrawRecord bestMove(PlayerColor rootPlayer) {
            SearchNode best = children.stream()
                    .max((left, right) -> {
                        int visitCompare = Integer.compare(left.visits, right.visits);
                        if (visitCompare != 0) return visitCompare;
                        return Double.compare(left.meanValue(rootPlayer), right.meanValue(rootPlayer));
                    })
                    .orElseThrow(() -> new IllegalStateException("No legal draws available"));
            return best.moveFromParent;
        }
    }
}

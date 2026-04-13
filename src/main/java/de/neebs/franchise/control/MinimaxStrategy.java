package de.neebs.franchise.control;

import de.neebs.franchise.entity.City;
import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import de.neebs.franchise.entity.Score;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Pure maxn Minimax: at each node the current player selects the move that
 * maximises their own score in the returned score-vector. No pruning.
 */
@Component("MINIMAX")
public class MinimaxStrategy implements GameStrategy {

    private static final int DEFAULT_DEPTH = 3;

    private final FranchiseService franchiseService;

    public MinimaxStrategy(@Lazy FranchiseService franchiseService) {
        this.franchiseService = franchiseService;
    }

    @Override
    public DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params) {
        int depth = parseDepth(params);
        List<DrawRecord> moves = franchiseService.getPossibleDrawsForState(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("No legal draws available for " + player);
        }

        DrawRecord best = null;
        int bestScore = Integer.MIN_VALUE;
        for (DrawRecord move : moves) {
            GameState next = franchiseService.applyDrawOnState(state, move);
            int score = maxn(next, depth - 1, params).get(player);
            if (score > bestScore) {
                bestScore = score;
                best = move;
            }
        }
        return best;
    }

    // Returns a score-vector for all players; each player maximises their own entry.
    Map<PlayerColor, Integer> maxn(GameState state, int depth, Map<String, Object> params) {
        if (depth == 0 || state.isEnd()) {
            return evaluate(state, params);
        }
        PlayerColor mover = state.getPlayers().get(state.getCurrentPlayerIndex());
        List<DrawRecord> moves = franchiseService.getPossibleDrawsForState(state);
        if (moves.isEmpty()) {
            return evaluate(state, params);
        }

        Map<PlayerColor, Integer> best = null;
        for (DrawRecord move : moves) {
            GameState next = franchiseService.applyDrawOnState(state, move);
            Map<PlayerColor, Integer> scores = maxn(next, depth - 1, params);
            if (best == null || scores.get(mover) > best.get(mover)) {
                best = scores;
            }
        }
        return best;
    }

    /**
     * Evaluates the game state for all players.
     *
     * <p>Selectable via {@code params.evalMode}:
     * <ul>
     *   <li><b>BALANCED</b> (default) – influence + income×incomeWeight + money/3 + bonusTiles×2.
     *       Rewards both current score and future earning potential, making the AI
     *       value city spread and cash alongside raw influence.</li>
     *   <li><b>FINAL_SCORE</b> – mirrors the actual end-game formula:
     *       influence + money/3 + bonusTiles×4 + 1 per small-town branch.</li>
     *   <li><b>INFLUENCE</b> – raw influence only (simple baseline).</li>
     * </ul>
     *
     * <p>{@code params.incomeWeight} (integer, default 2) scales the income term in BALANCED mode.
     */
    static Map<PlayerColor, Integer> evaluate(GameState state, Map<String, Object> params) {
        String mode = parseString(params, "evalMode", "BALANCED");
        int incomeWeight = parseInt(params, "incomeWeight", 2);

        Map<PlayerColor, Integer> result = new EnumMap<>(PlayerColor.class);
        for (Map.Entry<PlayerColor, Score> e : state.getScores().entrySet()) {
            PlayerColor p = e.getKey();
            Score s = e.getValue();
            int score = switch (mode) {
                case "INFLUENCE" -> s.getInfluence();
                case "FINAL_SCORE" -> s.getInfluence()
                        + s.getMoney() / 3
                        + s.getBonusTiles() * 4
                        + countSmallTownBranches(state, p);
                default -> // BALANCED
                    s.getInfluence()
                        + s.getIncome() * incomeWeight
                        + s.getMoney() / 3
                        + s.getBonusTiles() * 2;
            };
            result.put(p, score);
        }
        return result;
    }

    private static int countSmallTownBranches(GameState state, PlayerColor player) {
        int count = 0;
        for (City town : City.getTowns()) {
            if (player == state.getCityBranches().get(town)[0]) count++;
        }
        return count;
    }

    static int parseDepth(Map<String, Object> params) {
        return parseInt(params, "depth", DEFAULT_DEPTH);
    }

    static int parseInt(Map<String, Object> params, String key, int defaultValue) {
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v instanceof Number n) return n.intValue();
        return defaultValue;
    }

    static String parseString(Map<String, Object> params, String key, String defaultValue) {
        if (params == null) return defaultValue;
        Object v = params.get(key);
        if (v instanceof String s) return s;
        return defaultValue;
    }
}

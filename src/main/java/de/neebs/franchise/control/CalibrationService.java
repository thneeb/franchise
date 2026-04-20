package de.neebs.franchise.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.neebs.franchise.entity.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class CalibrationService {

    private static final String CALIBRATION_DIR = "calibration/";
    private static final List<Integer> EARLY_WEIGHTS = List.of(1, 2, 3, 4, 5);
    private static final List<Integer> LATE_WEIGHTS  = List.of(0, 1, 2);

    private final FranchiseService franchiseService;
    private final ObjectMapper objectMapper;
    private final Map<Integer, CalibrationConfig> cache = new ConcurrentHashMap<>();

    public CalibrationService(@Lazy FranchiseService franchiseService, ObjectMapper objectMapper) {
        this.franchiseService = franchiseService;
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // Load
    // -------------------------------------------------------------------------

    public CalibrationConfig loadConfig(int playerCount) {
        return cache.computeIfAbsent(playerCount, this::loadFromClasspath);
    }

    private CalibrationConfig loadFromClasspath(int playerCount) {
        String path = CALIBRATION_DIR + fileName(playerCount);
        try {
            ClassPathResource resource = new ClassPathResource(path);
            if (!resource.exists()) return null;
            return objectMapper.readValue(resource.getInputStream(), CalibrationConfig.class);
        } catch (IOException e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Calibrate
    // -------------------------------------------------------------------------

    public CalibrationConfig calibrate(int playerCount, int gamesPerMatchup, int depth) {
        List<EvalParams> candidates = buildCandidates();
        Map<EvalParams, Integer> wins = new LinkedHashMap<>();
        candidates.forEach(c -> wins.put(c, 0));

        // Round-robin: every pair plays gamesPerMatchup games
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                EvalParams a = candidates.get(i);
                EvalParams b = candidates.get(j);
                int half = gamesPerMatchup / 2;
                // a goes first in half the games, b goes first in the other half
                playMatchup(a, b, half, depth, playerCount, wins);
                playMatchup(b, a, half, depth, playerCount, wins);
            }
        }

        // Build rankings sorted by wins descending
        List<EvalParamsRanking> rankings = new ArrayList<>();
        int totalGames = (candidates.size() * (candidates.size() - 1) / 2) * gamesPerMatchup;
        wins.entrySet().stream()
                .sorted(Map.Entry.<EvalParams, Integer>comparingByValue().reversed())
                .forEach(e -> {
                    double winRate = totalGames > 0
                            ? (double) e.getValue() / totalGames : 0.0;
                    rankings.add(new EvalParamsRanking(
                            e.getKey().getEarlyIncomeWeight(),
                            e.getKey().getLateIncomeWeight(),
                            e.getValue(),
                            Math.round(winRate * 1000.0) / 1000.0));
                });

        EvalParams winner = rankings.get(0);

        CalibrationConfig config = new CalibrationConfig();
        config.setPlayerCount(playerCount);
        config.setCalibratedAt(LocalDate.now().toString());
        config.setGamesPerMatchup(gamesPerMatchup);
        config.setDepth(depth);
        config.setWinner(new EvalParams(winner.getEarlyIncomeWeight(), winner.getLateIncomeWeight()));
        config.setRankings(rankings);

        saveConfig(config);
        cache.put(playerCount, config);
        return config;
    }

    // -------------------------------------------------------------------------
    // Tournament helpers
    // -------------------------------------------------------------------------

    private void playMatchup(EvalParams first, EvalParams second, int games, int depth,
                              int playerCount, Map<EvalParams, Integer> wins) {
        List<PlayerColor> players = buildPlayerList(playerCount);

        // first config plays as players[0], second as the rest
        Map<PlayerColor, String> strategies = new LinkedHashMap<>();
        Map<String, Object> firstParams  = buildParams(first, depth);
        Map<String, Object> secondParams = buildParams(second, depth);

        // We run games where players[0] uses firstParams, all others use secondParams.
        // We achieve this by using a composite strategy name scheme via a wrapper approach.
        // Instead, we run the game manually here using applyDrawOnState simulation.
        for (int g = 0; g < games; g++) {
            PlayerColor winner = playOneGame(players, first, second, depth);
            if (winner == players.get(0)) {
                wins.merge(first, 1, Integer::sum);
            } else {
                wins.merge(second, 1, Integer::sum);
            }
        }
    }

    private PlayerColor playOneGame(List<PlayerColor> players, EvalParams firstParams,
                                     EvalParams secondParams, int depth) {
        // Build a transient game state (not stored in games map)
        String tmpId = UUID.randomUUID().toString();
        GameState state = franchiseService.buildInitialStatePublic(tmpId, players);

        // Play the game without storing it — use applyDrawOnState for simulation
        while (!state.isEnd()) {
            PlayerColor current = state.getPlayers().get(state.getCurrentPlayerIndex());
            // players[0] uses firstParams, others use secondParams
            EvalParams params = current == players.get(0) ? firstParams : secondParams;
            Map<String, Object> moveParams = buildParams(params, depth);

            List<DrawRecord> moves = franchiseService.getPossibleStrategyDrawsForState(state);
            DrawRecord best = selectBestMove(state, current, moves, depth, moveParams);
            state = franchiseService.applyDrawOnState(state, best);
        }

        // Winner = highest influence (ties broken by money)
        GameState finalState = state;
        return finalState.getScores().entrySet().stream()
                .max(Comparator.comparingInt((Map.Entry<PlayerColor, de.neebs.franchise.entity.Score> e) ->
                                e.getValue().getInfluence())
                        .thenComparingInt(e -> e.getValue().getMoney()))
                .map(Map.Entry::getKey)
                .orElse(players.get(0));
    }

    private DrawRecord selectBestMove(GameState state, PlayerColor player,
                                       List<DrawRecord> moves, int depth,
                                       Map<String, Object> params) {
        if (moves.isEmpty()) {
            throw new IllegalStateException("No moves available for " + player);
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

    private Map<PlayerColor, Integer> maxn(GameState state, int depth, Map<String, Object> params) {
        if (depth == 0 || state.isEnd()) {
            return MinimaxStrategy.evaluate(state, params);
        }
        PlayerColor mover = state.getPlayers().get(state.getCurrentPlayerIndex());
        List<DrawRecord> moves = franchiseService.getPossibleStrategyDrawsForState(state);
        if (moves.isEmpty()) {
            return MinimaxStrategy.evaluate(state, params);
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

    // -------------------------------------------------------------------------
    // Persistence
    // -------------------------------------------------------------------------

    private void saveConfig(CalibrationConfig config) {
        try {
            // Resolve path relative to the Maven project src/main/resources directory
            // so the file gets committed to git alongside source code.
            String projectRoot = System.getProperty("user.dir");
            File dir = new File(projectRoot, "src/main/resources/" + CALIBRATION_DIR);
            dir.mkdirs();
            File file = new File(dir, fileName(config.getPlayerCount()));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, config);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save calibration config", e);
        }
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static List<EvalParams> buildCandidates() {
        List<EvalParams> result = new ArrayList<>();
        for (int early : EARLY_WEIGHTS) {
            for (int late : LATE_WEIGHTS) {
                result.add(new EvalParams(early, late));
            }
        }
        return result;
    }

    private static List<PlayerColor> buildPlayerList(int playerCount) {
        List<PlayerColor> all = List.of(PlayerColor.values());
        return new ArrayList<>(all.subList(0, playerCount));
    }

    private static Map<String, Object> buildParams(EvalParams params, int depth) {
        Map<String, Object> map = new HashMap<>();
        map.put("evalMode", "AUTO_RESOLVED");
        map.put("depth", depth);
        map.put("calibEarlyWeight", params.getEarlyIncomeWeight());
        map.put("calibLateWeight", params.getLateIncomeWeight());
        return map;
    }

    private static String fileName(int playerCount) {
        return "calibration-" + playerCount + "p.json";
    }
}

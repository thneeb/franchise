package de.neebs.franchise.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.neebs.franchise.entity.City;
import de.neebs.franchise.entity.DrawRecord;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import de.neebs.franchise.entity.Region;
import de.neebs.franchise.entity.Score;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computer strategy that delegates move selection to an LLM via the OpenRouter API.
 * Falls back to the first non-skip candidate if the API call fails.
 *
 * Params:
 *   model        — OpenRouter model ID (default: anthropic/claude-haiku-4-5)
 *   logReason    — if "true", logs the LLM's reasoning to stdout (default: false)
 */
@Component("LLM")
public class LlmStrategy implements GameStrategy {

    private static final Logger log = LoggerFactory.getLogger(LlmStrategy.class);
    private static final String DEFAULT_MODEL = "anthropic/claude-haiku-4-5";
    private static final String SYSTEM_PROMPT_PATH = "llm/system-prompt.md";

    private final FranchiseService franchiseService;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final String systemPrompt;

    public LlmStrategy(@Lazy FranchiseService franchiseService,
                       LlmClient llmClient,
                       ObjectMapper objectMapper) {
        this.franchiseService = franchiseService;
        this.llmClient = llmClient;
        this.objectMapper = objectMapper;
        this.systemPrompt = loadSystemPrompt();
    }

    @Override
    public DrawRecord selectDraw(GameState state, PlayerColor player, Map<String, Object> params) {
        List<DrawRecord> moves = franchiseService.getPossibleStrategyDrawsForState(state);
        if (moves.isEmpty()) {
            throw new IllegalStateException("No legal draws available for " + player);
        }

        // Remove skip moves when actionable moves exist
        List<DrawRecord> candidates = filterSkip(moves);

        String model = parseString(params, "model", DEFAULT_MODEL);

        String userPrompt = buildUserPrompt(state, player, candidates);
        String response = llmClient.complete(model, systemPrompt, userPrompt);

        int chosen = parseChosenIndex(response, candidates.size());
        DrawRecord chosenMove = candidates.get(chosen);
        chosenMove.setReason(extractReason(response));

        return chosenMove;
    }

    // -------------------------------------------------------------------------
    // Prompt building
    // -------------------------------------------------------------------------

    private String buildUserPrompt(GameState state, PlayerColor player, List<DrawRecord> candidates) {
        PlayerColor opponent = state.getPlayers().stream()
                .filter(p -> p != player).findFirst().orElse(null);
        Score myScore = state.getScores().get(player);
        Score oppScore = opponent != null ? state.getScores().get(opponent) : null;

        StringBuilder sb = new StringBuilder();
        sb.append("## Current Game State\n");
        sb.append(String.format("Round: %d | Your color: %s%s\n\n",
                state.getRound(), player,
                state.isInitialization() ? " (INITIALIZATION — place your starting city)" : ""));

        // Scores
        sb.append("**Scores:**\n");
        if (myScore != null) {
            sb.append(String.format("- YOU (%s): influence=%d, income=%d, money=%d, bonusTiles=%d\n",
                    player, myScore.getInfluence(), myScore.getIncome(),
                    myScore.getMoney(), myScore.getBonusTiles()));
        }
        if (oppScore != null && opponent != null) {
            sb.append(String.format("- OPPONENT (%s): influence=%d, income=%d, money=%d, bonusTiles=%d\n",
                    opponent, oppScore.getInfluence(), oppScore.getIncome(),
                    oppScore.getMoney(), oppScore.getBonusTiles()));
        }
        sb.append('\n');

        // Board by region
        sb.append("**Board State by Region** (YOU / OPP / empty slots):\n");
        for (Region region : Region.values()) {
            int myBranches = 0, oppBranches = 0;
            List<String> cityLines = region.getCities().stream()
                    .sorted(java.util.Comparator.comparing(Enum::name))
                    .map(city -> {
                        PlayerColor[] slots = state.getCityBranches().get(city);
                        if (slots == null) return null;
                        long mine = Arrays.stream(slots).filter(b -> b == player).count();
                        long opp = opponent != null ? Arrays.stream(slots).filter(b -> b == opponent).count() : 0;
                        long empty = Arrays.stream(slots).filter(b -> b == null).count();
                        String who = mine > 0 && opp > 0 ? "BOTH" :
                                mine > 0 ? "YOU" : opp > 0 ? "OPP" : "empty";
                        return String.format("    %s [%s: you=%d opp=%d free=%d]",
                                city.name(), who, mine, opp, empty);
                    })
                    .filter(s -> s != null)
                    .collect(Collectors.toList());

            // count totals per region
            int[] totals = branchTotals(region, state, player, opponent);
            sb.append(String.format("  %s (bonus 1st=%d/2nd=%d/3rd=%d) — you:%d opp:%d\n",
                    region.getName(), region.getFirst(), region.getSecond(), region.getThird(),
                    totals[0], totals[1]));
            cityLines.forEach(line -> sb.append(line).append('\n'));
        }
        sb.append('\n');

        // Possible moves
        sb.append("**Your Possible Moves** (choose one by index, 0-based):\n");
        for (int i = 0; i < candidates.size(); i++) {
            sb.append(String.format("  %d: %s\n", i, formatMove(candidates.get(i))));
        }
        sb.append('\n');
        sb.append("Respond with JSON only: {\"moveIndex\": <int>, \"reason\": \"<explanation>\"}\n");

        return sb.toString();
    }

    private int[] branchTotals(Region region, GameState state, PlayerColor player, PlayerColor opponent) {
        int mine = 0, opp = 0;
        for (City city : region.getCities()) {
            PlayerColor[] slots = state.getCityBranches().get(city);
            if (slots == null) continue;
            for (PlayerColor slot : slots) {
                if (slot == player) mine++;
                else if (slot == opponent) opp++;
            }
        }
        return new int[]{mine, opp};
    }

    private static String formatMove(DrawRecord move) {
        StringBuilder sb = new StringBuilder();
        if (!move.getExtension().isEmpty()) {
            sb.append("EXT(");
            sb.append(move.getExtension().stream().map(City::name).collect(Collectors.joining(", ")));
            sb.append(')');
        }
        if (!move.getIncrease().isEmpty()) {
            if (sb.length() > 0) sb.append(" + ");
            sb.append("INC(");
            sb.append(move.getIncrease().stream().map(City::name).collect(Collectors.joining(", ")));
            sb.append(')');
        }
        if (move.getBonusTileUsage() != null) {
            sb.append(" [BONUS:").append(move.getBonusTileUsage()).append(']');
        }
        if (sb.length() == 0) sb.append("SKIP");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    private int parseChosenIndex(String response, int maxIndex) {
        if (response == null || response.isBlank()) return 0;
        try {
            // Strip markdown code fences if present
            String cleaned = response.replaceAll("(?s)```[a-z]*\\s*", "").trim();
            // Find the first JSON object
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            }
            JsonNode node = objectMapper.readTree(cleaned);
            int idx = node.path("moveIndex").asInt(-1);
            if (idx >= 0 && idx < maxIndex) return idx;
        } catch (Exception ignored) {}
        // Regex fallback
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"moveIndex\"\\s*:\\s*(\\d+)").matcher(response);
        if (m.find()) {
            int idx = Integer.parseInt(m.group(1));
            if (idx >= 0 && idx < maxIndex) return idx;
        }
        log.warn("[LLM] Could not parse moveIndex from response, using index 0. Response: {}",
                response.length() > 200 ? response.substring(0, 200) + "..." : response);
        return 0;
    }

    private String extractReason(String response) {
        try {
            String cleaned = response.replaceAll("(?s)```[a-z]*\\s*", "").trim();
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = objectMapper.readTree(cleaned.substring(start, end + 1));
                return node.path("reason").asText("(no reason)");
            }
        } catch (Exception ignored) {}
        return "(parse error)";
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<DrawRecord> filterSkip(List<DrawRecord> moves) {
        List<DrawRecord> actionable = moves.stream()
                .filter(m -> !m.getExtension().isEmpty() || !m.getIncrease().isEmpty())
                .collect(Collectors.toList());
        return actionable.isEmpty() ? moves : actionable;
    }

    private static String parseString(Map<String, Object> params, String key, String defaultValue) {
        if (params == null) return defaultValue;
        Object value = params.get(key);
        if (value instanceof String s && !s.isBlank()) return s.trim();
        return defaultValue;
    }

    private static String loadSystemPrompt() {
        try {
            ClassPathResource resource = new ClassPathResource(SYSTEM_PROMPT_PATH);
            if (resource.exists()) {
                return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("Could not load LLM system prompt from {}: {}", SYSTEM_PROMPT_PATH, e.getMessage());
        }
        return "You are playing the board game Franchise. Pick the best move. Respond with JSON: {\"moveIndex\": <int>, \"reason\": \"<text>\"}";
    }
}

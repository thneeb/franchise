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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Computer strategy that delegates move selection to an LLM via the OpenRouter API.
 * Falls back to the first non-skip candidate if the API call fails.
 *
 * Params:
 *   model  — OpenRouter model ID (default: anthropic/claude-haiku-4-5)
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
        Map<City, Integer> extensionCosts = franchiseService.computeExpansionCosts(state);

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

        // Region majority analysis — shows what's actually needed to close each region
        sb.append("**Region Majority Analysis** (regions close only when ALL large cities are SCORED via majority):\n");
        for (Region region : Region.values()) {
            if (state.getInactiveRegions().contains(region)) continue;

            int yourTotal = 0, oppTotal = 0;
            for (City c : region.getCities()) {
                PlayerColor[] slots = state.getCityBranches().get(c);
                if (slots == null) continue;
                for (PlayerColor s : slots) {
                    if (s == player) yourTotal++;
                    else if (s == opponent) oppTotal++;
                }
            }

            if (state.getClosedRegions().contains(region)) {
                String winner = yourTotal > oppTotal ? "YOU 1st" : oppTotal > yourTotal ? "OPP 1st" : "TIED";
                sb.append(String.format("  %s [CLOSED — %s]\n", region.getName(), winner));
                continue;
            }

            String verdict;
            if (yourTotal > oppTotal) verdict = "✅ CLOSE NOW — you lead; keep increasing to achieve majority in each city";
            else if (oppTotal > yourTotal) verdict = "⚠️ DO NOT TRIGGER CLOSURE — you trail; do NOT score the last unscored city (that closes the region). You CAN still extend into new cities here.";
            else verdict = "CONTEST — tied; race to majority in each city, whoever scores first wins";

            sb.append(String.format("  %s (you:%d opp:%d) → %s\n",
                    region.getName(), yourTotal, oppTotal, verdict));

            for (City city : region.getCities().stream()
                    .sorted(java.util.Comparator.comparing(Enum::name))
                    .collect(Collectors.toList())) {

                PlayerColor[] slots = state.getCityBranches().get(city);
                if (city.getSize() == 1) {
                    boolean entered = slots != null && Arrays.stream(slots).anyMatch(s -> s != null);
                    if (!entered) {
                        sb.append(String.format("    %s [town]: NOT ENTERED — need 1 extend\n", city.name()));
                    }
                } else {
                    if (state.getClosedCities().contains(city)) {
                        sb.append(String.format("    %s: SCORED ✓\n", city.name()));
                    } else {
                        int total = city.getSize();
                        int majorityNeeded = total / 2 + 1;
                        int myB = 0, oppB = 0;
                        if (slots != null) {
                            for (PlayerColor s : slots) {
                                if (s == player) myB++;
                                else if (s == opponent) oppB++;
                            }
                        }
                        String myStr = myB == 0
                                ? String.format("YOU not here (need 1 ext + %d inc)", majorityNeeded)
                                : myB * 2 > total
                                        ? "YOU have majority already — score imminent"
                                        : String.format("YOU %d/%d → need %d more increases", myB, total, majorityNeeded - myB);
                        String oppStr = oppB == 0
                                ? "OPP not here"
                                : oppB * 2 > total
                                        ? "OPP has majority — will score next!"
                                        : String.format("OPP %d/%d → needs %d more", oppB, total, majorityNeeded - oppB);
                        sb.append(String.format("    %s [%d slots, majority=%d+, bonus=%dpts]: %s | %s\n",
                                city.name(), total, majorityNeeded, total, myStr, oppStr));
                    }
                }
            }
        }
        sb.append('\n');

        // Possible moves
        sb.append("**Your Possible Moves** (choose one by index, 0-based):\n");
        boolean hasSkip = false;
        for (int i = 0; i < candidates.size(); i++) {
            DrawRecord m = candidates.get(i);
            boolean isSkip = m.getExtension().isEmpty() && m.getIncrease().isEmpty();
            if (isSkip) hasSkip = true;
            sb.append(String.format("  %d: %s\n", i, formatMove(m, extensionCosts, state, player)));
        }
        if (hasSkip) {
            sb.append("\n⚠️ WARNING: The move list contains SKIP. SKIP is almost always the worst choice.\n");
            sb.append("  SKIP earns you nothing while your opponent acts freely. Only pick SKIP if you have\n");
            sb.append("  literally zero money AND zero income AND no other option. If ANY extension or\n");
            sb.append("  increase is available, prefer it over SKIP — even entering a distant city is better\n");
            sb.append("  than giving your opponent a free turn.\n");
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

    private static String formatMove(DrawRecord move, Map<City, Integer> costs,
                                     GameState state, PlayerColor player) {
        StringBuilder sb = new StringBuilder();
        if (!move.getExtension().isEmpty()) {
            sb.append("EXT(");
            sb.append(move.getExtension().stream()
                    .map(c -> {
                        int cost = costs.getOrDefault(c, 0);
                        String note = cost == 0 ? "FREE" : "$" + cost;
                        return c.name() + "(" + note + ")";
                    })
                    .collect(Collectors.joining(", ")));
            sb.append(')');
        }
        if (!move.getIncrease().isEmpty()) {
            if (!sb.isEmpty()) sb.append(" + ");
            sb.append("INC(");
            sb.append(move.getIncrease().stream()
                    .map(c -> {
                        PlayerColor[] slots = state.getCityBranches().get(c);
                        if (slots == null) return c.name();
                        int myB = 0;
                        for (PlayerColor s : slots) if (s == player) myB++;
                        int total = c.getSize();
                        int needed = total / 2 + 1;
                        String tag = (myB + 1 >= needed) ? "→MAJORITY!" : (myB + 1) + "/" + total;
                        return c.name() + "(" + tag + ")";
                    })
                    .collect(Collectors.joining(", ")));
            sb.append(')');
        }
        if (move.getBonusTileUsage() != null) {
            sb.append(" [BONUS:").append(move.getBonusTileUsage()).append(']');
        }
        if (sb.isEmpty()) sb.append("SKIP");
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

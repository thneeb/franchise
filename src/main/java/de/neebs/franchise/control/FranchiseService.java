package de.neebs.franchise.control;

import de.neebs.franchise.entity.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.OptionalInt;

@Service
public class FranchiseService {

    private static final int INITIAL_SUPPLY = 40;
    private static final int RED_ZONE_INDEX = 8; // red zone = positions 8-10 (1-indexed); game ends when 1st tile reaches position 8, i.e. regionTrackIndex reaches 8
    private static final int MAX_DRAWS_PER_GAME = 120;

    private final Map<String, GameState> games = new ConcurrentHashMap<>();
    private final Map<String, de.neebs.franchise.entity.LearningProgress> learningRuns = new ConcurrentHashMap<>();

    // Injected lazily to avoid circular dependency (strategies → service → strategies)
    private Map<String, GameStrategy> strategies = Map.of();
    private CalibrationService calibrationService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStrategies(Map<String, GameStrategy> strategies) {
        this.strategies = new LinkedHashMap<>(strategies);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void setCalibrationService(@org.springframework.context.annotation.Lazy CalibrationService calibrationService) {
        this.calibrationService = calibrationService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public GameState initGame(List<PlayerColor> players) {
        String id = UUID.randomUUID().toString();
        GameState state = buildInitialState(id, players);
        games.put(id, state);
        return state;
    }

    public GameState getGame(String id) {
        GameState state = games.get(id);
        if (state == null) throw new NoSuchElementException("Game not found: " + id);
        return state;
    }

    public DrawResult applyDraw(String gameId, DrawRecord draw) {
        long start = System.nanoTime();
        GameState state = getGame(gameId);
        PlayerColor player = currentPlayer(state);

        if (draw.getColor() != player) {
            throw new IllegalArgumentException("Not your turn");
        }

        List<String> influenceLog = new ArrayList<>();
        List<InfluenceEvent> influenceEvents = new ArrayList<>();
        int income = 0;
        DrawCostSummary costSummary;

        if (state.isInitialization()) {
            income = applyInitDraw(state, player, draw);
            costSummary = buildInitDrawCostSummary();
        } else {
            costSummary = validateAndCalculateCosts(state, player, draw);
            income = applyNormalDraw(state, player, draw, influenceLog, influenceEvents, costSummary);
        }

        state.getDrawHistory().add(draw);
        checkDrawLimitLoss(state, player);
        state.getInfluenceHistory().addAll(influenceEvents);
        return new DrawResult(draw, income, influenceLog, influenceEvents,
                state.getScores().get(player).getMoney(), state.isEnd(), getWinners(state), costSummary,
                System.nanoTime() - start);
    }

    public List<DrawRecord> getPossibleDraws(String gameId) {
        GameState state = getGame(gameId);
        return getPossibleDrawsFromState(state);
    }

    // Core move-generation logic operating directly on a GameState (no HashMap lookup).
    private List<DrawRecord> getPossibleDrawsFromState(GameState state) {
        if (state.isEnd()) return List.of();
        PlayerColor player = currentPlayer(state);
        List<DrawRecord> draws = new ArrayList<>();

        if (state.isInitialization()) {
            for (City town : City.getTowns()) {
                if (state.getCityBranches().get(town)[0] == null) {
                    // Only offer towns that have at least one reachable non-closed expansion target.
                    // This prevents placing in inactive-region towns or geographically isolated starts.
                    Set<City> targets = validExpansionTargetsFrom(state, player, Set.of(town));
                    if (!targets.isEmpty()) {
                        draws.add(draw(player, List.of(town), List.of(), null));
                    }
                }
            }
            return draws;
        }

        Score score = state.getScores().get(player);
        int income = calcIncome(state, player);
        int availableMoney = score.getMoney() + income;
        boolean bonusEligible = state.getRound() > state.getPlayers().size()
                && score.getBonusTiles() > 0;

        // Precompute once — avoids O(N²) recomputation inside nested loops
        Set<City> myCities = citiesWithPresence(state, player);
        Set<City> expansionTargets = validExpansionTargetsFrom(state, player, myCities);
        Map<City, Integer> costMap = expansionCostMap(myCities, expansionTargets);
        Set<City> increaseCities = validIncreaseCities(state, player, List.of());

        // --- No bonus tile ---

        // Skip (always available)
        draws.add(draw(player, List.of(), List.of(), null));

        // Single extension (+ optional single increase)
        for (City ext : expansionTargets) {
            int extCost = costMap.get(ext);
            if (availableMoney >= extCost) {
                draws.add(draw(player, List.of(ext), List.of(), null));
                for (City inc : validIncreaseCities(state, player, List.of(ext))) {
                    if (availableMoney >= extCost + 1) {
                        draws.add(draw(player, List.of(ext), List.of(inc), null));
                    }
                }
            }
        }

        // Single increase only
        for (City inc : increaseCities) {
            if (availableMoney >= 1) {
                draws.add(draw(player, List.of(), List.of(inc), null));
            }
        }

        // --- Bonus tile draws ---
        if (bonusEligible) {
            // MONEY bonus: take $10, then optionally expand/increase
            int moneyAvailable = availableMoney + 10;
            draws.add(draw(player, List.of(), List.of(), BonusTileUsage.MONEY));
            for (City ext : expansionTargets) {
                int extCost = costMap.get(ext);
                if (moneyAvailable >= extCost) {
                    draws.add(draw(player, List.of(ext), List.of(), BonusTileUsage.MONEY));
                    for (City inc : validIncreaseCities(state, player, List.of(ext))) {
                        if (moneyAvailable >= extCost + 1) {
                            draws.add(draw(player, List.of(ext), List.of(inc), BonusTileUsage.MONEY));
                        }
                    }
                }
            }
            for (City inc : increaseCities) {
                if (moneyAvailable >= 1) {
                    draws.add(draw(player, List.of(), List.of(inc), BonusTileUsage.MONEY));
                }
            }

            // EXTENSION bonus: expand to exactly 2 cities
            List<City> extList = new ArrayList<>(expansionTargets);
            for (int i = 0; i < extList.size(); i++) {
                for (int j = i + 1; j < extList.size(); j++) {
                    City ext1 = extList.get(i);
                    City ext2 = extList.get(j);
                    int cost1 = costMap.get(ext1);
                    int cost2 = costMap.get(ext2);
                    if (availableMoney >= cost1 + cost2) {
                        draws.add(draw(player, List.of(ext1, ext2), List.of(), BonusTileUsage.EXTENSION));
                        for (City inc : validIncreaseCities(state, player, List.of(ext1, ext2))) {
                            if (availableMoney >= cost1 + cost2 + 1) {
                                draws.add(draw(player, List.of(ext1, ext2), List.of(inc), BonusTileUsage.EXTENSION));
                            }
                        }
                    }
                }
            }

            // INCREASE bonus: double-increase in one city (needs ≥ 2 free slots, costs $1)
            // Represented as increase:[city, city] to make both placements explicit
            Set<City> doubleIncreaseCities = validIncreaseCities(state, player, List.of(), 2);
            for (City inc : doubleIncreaseCities) {
                if (availableMoney >= 1) {
                    draws.add(draw(player, List.of(), List.of(inc, inc), BonusTileUsage.INCREASE));
                    for (City ext : expansionTargets) {
                        int extCost = costMap.get(ext);
                        if (availableMoney >= extCost + 1) {
                            draws.add(draw(player, List.of(ext), List.of(inc, inc), BonusTileUsage.INCREASE));
                        }
                    }
                }
            }
        }

        return draws;
    }

    public DrawRecord getDraw(String gameId, int index) {
        GameState state = getGame(gameId);
        return state.getDrawHistory().get(index);
    }

    // Returns all legal draws for an arbitrary state.
    public List<DrawRecord> getPossibleDrawsForState(GameState state) {
        return getPossibleDrawsFromState(state);
    }

    // AI-only move generation: if any move expands into a new city, require expansion.
    public List<DrawRecord> getPossibleStrategyDrawsForState(GameState state) {
        return getPossibleDrawsFromState(state);
    }

    // Deep-copies state, applies the draw to the copy, and returns the mutated copy
    public GameState applyDrawOnState(GameState state, DrawRecord draw) {
        GameState copy = state.deepCopy();
        applyDrawToState(copy, draw);
        return copy;
    }

    // Applies a draw directly to a GameState (mutable) — used by simulation and replay paths.
    // Skips all validation; moves must already be legal (generated by move generation).
    void applyDrawToState(GameState state, DrawRecord draw) {
        PlayerColor player = currentPlayer(state);
        if (state.isInitialization()) {
            applyInitDraw(state, player, draw);
        } else {
            // Precompute myCities and costMap once rather than recomputing in each validation call
            Set<City> myCities = citiesWithPresence(state, player);
            List<City> extensions = draw.getExtension() != null ? draw.getExtension() : List.of();
            Map<City, Integer> costMap = expansionCostMap(myCities, new HashSet<>(extensions));
            applyNormalDrawFast(state, player, draw, myCities, costMap);
        }
        state.getDrawHistory().add(draw);
        checkDrawLimitLoss(state, player);
    }

    // Fast path for simulation: skip all validation, use precomputed costs.
    private void applyNormalDrawFast(GameState state, PlayerColor player, DrawRecord draw,
                                      Set<City> myCities, Map<City, Integer> costMap) {
        BonusTileUsage bonus = draw.getBonusTileUsage();
        List<City> extensions = draw.getExtension() != null ? draw.getExtension() : List.of();
        List<City> increases = draw.getIncrease() != null ? draw.getIncrease() : List.of();

        int income = calcIncome(state, player);
        Score score = state.getScores().get(player);

        if (bonus != null) {
            score.setBonusTiles(score.getBonusTiles() - 1);
            if (bonus == BonusTileUsage.MONEY) {
                score.setMoney(score.getMoney() + 10);
            }
        }

        // Phase 1: Income
        score.setMoney(score.getMoney() + income);
        score.setIncome(income);

        // Phase 2: Pay extension costs
        for (City target : extensions) {
            int cost = costMap.getOrDefault(target, 0);
            score.setMoney(score.getMoney() - cost);
        }

        // Phase 3: Pay increase costs
        if (bonus == BonusTileUsage.INCREASE) {
            score.setMoney(score.getMoney() - 1);
            state.getSupply().merge(player, -2, Integer::sum);
        } else {
            for (City city : increases) {
                score.setMoney(score.getMoney() - 1);
                state.getSupply().merge(player, -1, Integer::sum);
            }
        }

        // Phase 4A: Place extension branches
        List<String> log = new ArrayList<>();
        List<InfluenceEvent> events = new ArrayList<>();
        for (City target : extensions) {
            placeNextClockwise(state, player, target, log, events);
            state.getSupply().merge(player, -1, Integer::sum);
        }

        // Phase 4B: Place increase branches
        if (bonus == BonusTileUsage.INCREASE && !increases.isEmpty()) {
            placeNextClockwise(state, player, increases.get(0), log, events);
            placeNextClockwise(state, player, increases.get(0), log, events);
        } else {
            for (City city : increases) {
                placeNextClockwise(state, player, city, log, events);
            }
        }

        // Phase 5: Region scoring
        checkAllRegions(state, player, log, events);

        // Game end check (region track reached red zone)
        if (state.getRegionTrackIndex() >= RED_ZONE_INDEX) {
            doFinalScoring(state, log, events);
            state.setEnd(true);
        }

        // Advance turn
        state.setCurrentPlayerIndex(
                (state.getCurrentPlayerIndex() + 1) % state.getPlayers().size());
        state.setRound(state.getRound() + 1);
        state.getInfluenceHistory().addAll(events);
    }

    // Runs calibration tournament and persists the result
    public CalibrationConfig calibrate(int playerCount, int gamesPerMatchup, int depth) {
        return calibrationService.calibrate(playerCount, gamesPerMatchup, depth);
    }

    // Selects and applies the best draw for the current player using the given strategy
    public DrawResult computeBestDraw(String gameId, String strategyName, Map<String, Object> params) {
        return computeBestDraw(gameId, null, strategyName, params);
    }

    public DrawResult computeBestDraw(String gameId, PlayerColor requestedPlayer,
                                      String strategyName, Map<String, Object> params) {
        long start = System.nanoTime();
        GameStrategy strategy = strategies.get(strategyName);
        if (strategy == null) {
            throw new IllegalArgumentException("Strategy not implemented: " + strategyName);
        }
        GameState state = getGame(gameId);
        PlayerColor player = currentPlayer(state);
        if (requestedPlayer != null && requestedPlayer != player) {
            throw new IllegalArgumentException("Not your turn");
        }
        DrawRecord best = strategy.selectDraw(state, player, params);
        if (best.getColor() != player) {
            throw new IllegalArgumentException(
                    "Strategy selected a draw for " + best.getColor() + " but current player is " + player);
        }
        DrawResult result = applyDraw(gameId, best);
        return result.withProcessingTimeNanos(System.nanoTime() - start);
    }

    public de.neebs.franchise.entity.LearningProgress getLearningProgress(String runId) {
        return learningRuns.get(runId);
    }

    public LearningRunResult runGames(List<PlayerColor> players,
                                      Map<PlayerColor, String> playerStrategies,
                                      Map<PlayerColor, Map<String, Object>> playerParams,
                                      Set<String> learningModels,
                                      String runId,
                                      int timesToPlay) {
        long runStart = System.nanoTime();
        Map<PlayerColor, Integer> wins = new EnumMap<>(PlayerColor.class);
        players.forEach(p -> wins.put(p, 0));
        Map<PlayerColor, Long> processingTimes = new EnumMap<>(PlayerColor.class);
        players.forEach(p -> processingTimes.put(p, 0L));
        long snapshotTimes = 0L;
        long trainingTimes = 0L;
        long modelSaveTimes = 0L;
        boolean training = !learningModels.isEmpty();
        List<TrainingRunCount> trainingRunCounts = new ArrayList<>();
        if (training) {
            for (String modelName : learningModels) {
                GameStrategy strategy = strategies.get(modelName);
                if (strategy instanceof TrainableStrategy ts) {
                    trainingRunCounts.addAll(ts.getTrainingRunCounts(
                            players.size(),
                            playerStrategies,
                            playerParams,
                            modelName));
                }
            }
        }

        de.neebs.franchise.entity.LearningProgress progress = null;
        if (runId != null) {
            progress = new de.neebs.franchise.entity.LearningProgress(
                    runId,
                    timesToPlay,
                    players,
                    trainingRunCounts,
                    playerStrategies,
                    playerParams,
                    learningModels);
            learningRuns.put(runId, progress);
        }
        final de.neebs.franchise.entity.LearningProgress progressRef = progress;

        for (int i = 0; i < timesToPlay; i++) {
            String tmpId = UUID.randomUUID().toString();
            GameState state = buildInitialState(tmpId, players);
            games.put(tmpId, state);
            List<GameState> trajectory = training ? new ArrayList<>() : null;
            try {
                while (!games.get(tmpId).isEnd()) {
                    if (training) {
                        long snapshotStart = System.nanoTime();
                        trajectory.add(games.get(tmpId).deepCopy());
                        long snapshotNanos = System.nanoTime() - snapshotStart;
                        snapshotTimes += snapshotNanos;
                        if (progressRef != null) progressRef.recordSnapshotTime(snapshotNanos);
                    }
                    PlayerColor current = currentPlayer(games.get(tmpId));
                    String strategyName = playerStrategies.get(current);
                    Map<String, Object> params = playerParams.getOrDefault(current, Map.of());
                    DrawResult result = computeBestDraw(tmpId, strategyName, params);
                    processingTimes.merge(current, result.getProcessingTimeNanos(), Long::sum);
                    if (progressRef != null) {
                        progressRef.recordProcessingTime(current, result.getProcessingTimeNanos());
                    }
                }
                if (training) {
                    long snapshotStart = System.nanoTime();
                    trajectory.add(games.get(tmpId).deepCopy());
                    long snapshotNanos = System.nanoTime() - snapshotStart;
                    snapshotTimes += snapshotNanos;
                    if (progressRef != null) progressRef.recordSnapshotTime(snapshotNanos);
                }

                PlayerColor winner = games.get(tmpId).getScores().entrySet().stream()
                        .max(Map.Entry.comparingByValue(
                                Comparator.comparingInt(s -> s.getInfluence())))
                        .map(Map.Entry::getKey)
                        .orElseThrow();
                wins.merge(winner, 1, Integer::sum);
                if (progressRef != null) progressRef.increment(winner);

                if (training) {
                    Map<PlayerColor, Integer> finalScores = new EnumMap<>(PlayerColor.class);
                    games.get(tmpId).getScores().forEach((p, s) -> finalScores.put(p, s.getInfluence()));
                    for (String modelName : learningModels) {
                        GameStrategy strategy = strategies.get(modelName);
                        if (strategy instanceof TrainableStrategy ts) {
                            TrainingTimings timings = ts.onGameComplete(
                                    trajectory,
                                    finalScores,
                                    playerStrategies,
                                    playerParams);
                            trainingTimes += timings.trainingNanos();
                            modelSaveTimes += timings.modelSaveNanos();
                            trainingRunCounts = mergeTrainingRunCounts(trainingRunCounts, ts.getTrainingRunCounts(
                                    players.size(),
                                    playerStrategies,
                                    playerParams,
                                    modelName));
                            if (progressRef != null) {
                                progressRef.recordTrainingTime(timings.trainingNanos());
                                progressRef.recordModelSaveTime(timings.modelSaveNanos());
                                trainingRunCounts.forEach(progressRef::updateTrainingRuns);
                            }
                        }
                    }
                }
            } finally {
                games.remove(tmpId);
            }
        }
        return new LearningRunResult(wins, processingTimes, snapshotTimes, trainingTimes, modelSaveTimes,
                System.nanoTime() - runStart, trainingRunCounts, playerStrategies, playerParams, learningModels);
    }

    private static List<TrainingRunCount> mergeTrainingRunCounts(List<TrainingRunCount> existing,
                                                                 List<TrainingRunCount> updates) {
        Map<String, TrainingRunCount> merged = new LinkedHashMap<>();
        existing.forEach(count -> merged.put(trainingRunKey(count), count));
        updates.forEach(count -> merged.put(trainingRunKey(count), count));
        return new ArrayList<>(merged.values());
    }

    private static String trainingRunKey(TrainingRunCount count) {
        return count.strategy() + ":" + (count.trainingTarget() != null ? count.trainingTarget() : "");
    }

    public GameState undoDraws(String gameId, int fromIndex) {
        GameState state = getGame(gameId);
        List<DrawRecord> keep = new ArrayList<>(state.getDrawHistory().subList(0, fromIndex));
        List<PlayerColor> players = new ArrayList<>(state.getPlayers());

        GameState fresh = buildInitialState(gameId, players);
        games.put(gameId, fresh);

        for (DrawRecord draw : keep) {
            applyDraw(gameId, draw);
        }

        return games.get(gameId);
    }

    // -------------------------------------------------------------------------
    // Game initialization
    // -------------------------------------------------------------------------

    // Package-visible so CalibrationService can build transient states without storing them
    GameState buildInitialStatePublic(String id, List<PlayerColor> players) {
        return buildInitialState(id, players);
    }

    private GameState buildInitialState(String id, List<PlayerColor> players) {
        GameState state = new GameState();
        state.setId(id);
        state.setPlayers(new ArrayList<>(players));
        state.setInitialization(true);
        state.setEnd(false);
        state.setRound(0);
        // Initialization order is REVERSE of player order (last player picks first)
        state.setCurrentPlayerIndex(players.size() - 1);
        state.setScores(Rules.initScores(players));
        state.setSupply(initSupply(players));
        state.setCityBranches(initCityBranches());
        state.setClosedCities(new HashSet<>());
        state.setClosedRegions(new ArrayList<>());
        state.setRegionFirstScorer(new EnumMap<>(Region.class));
        state.setDrawHistory(new ArrayList<>());
        state.setInfluenceHistory(new ArrayList<>());

        // 2- or 3-player adjustment: three fixed regions are inactive.
        List<Region> inactive = new ArrayList<>();
        if (players.size() <= 3) {
            inactive = List.of(Region.CALIFORNIA, Region.MONTANA, Region.UPPER_WEST);
            PlayerColor neutralColor = findUnusedColor(players);
            applyInactiveRegions(state, inactive, neutralColor);
        }
        state.setInactiveRegions(new ArrayList<>(inactive));
        // Inactive regions are pre-scored at game start; advance the track index accordingly
        state.setRegionTrackIndex(inactive.size());

        return state;
    }

    private PlayerColor findUnusedColor(List<PlayerColor> players) {
        for (PlayerColor color : PlayerColor.values()) {
            if (!players.contains(color)) {
                return color;
            }
        }
        throw new IllegalStateException("No unused player color available for inactive regions");
    }

    private void applyInactiveRegions(GameState state, List<Region> inactive, PlayerColor neutralColor) {
        for (Region region : inactive) {
            for (City city : region.getCities()) {
                // Fill every slot with an unused player color — makes the city appear fully occupied
                Arrays.fill(state.getCityBranches().get(city), neutralColor);
                // City tiles are removed: treat them as already scored
                state.getClosedCities().add(city);
            }
            // Region itself is blocked — no scoring possible
            state.getClosedRegions().add(region);
        }
    }

    private Map<PlayerColor, Integer> initSupply(List<PlayerColor> players) {
        Map<PlayerColor, Integer> supply = new EnumMap<>(PlayerColor.class);
        for (PlayerColor p : players) supply.put(p, INITIAL_SUPPLY);
        return supply;
    }

    private Map<City, PlayerColor[]> initCityBranches() {
        Map<City, PlayerColor[]> map = new EnumMap<>(City.class);
        for (City city : City.values()) {
            map.put(city, new PlayerColor[city.getSize()]);
        }
        return map;
    }

    // -------------------------------------------------------------------------
    // Initialization draws
    // -------------------------------------------------------------------------

    private int applyInitDraw(GameState state, PlayerColor player, DrawRecord draw) {
        if (draw.getBonusTileUsage() != null) {
            throw new IllegalArgumentException("Bonus tiles cannot be used during initialization");
        }
        List<City> increases = draw.getIncrease() != null ? draw.getIncrease() : List.of();
        if (!increases.isEmpty()) {
            throw new IllegalArgumentException("Increases are not allowed during initialization");
        }
        List<City> extensions = draw.getExtension() != null ? draw.getExtension() : List.of();
        if (extensions.size() != 1) {
            throw new IllegalArgumentException("Only one city allowed during initialization");
        }
        City town = extensions.get(0);
        if (town.getSize() != 1) {
            throw new IllegalArgumentException(
                    "Only small towns (size 1) are allowed during initialization");
        }
        if (state.getCityBranches().get(town)[0] != null) {
            throw new IllegalArgumentException(
                    "Cannot initialize in " + town.getName() + ": town is already occupied");
        }
        if (state.getClosedCities().contains(town)) {
            throw new IllegalArgumentException(
                    "Cannot initialize in " + town.getName() + ": city is in an inactive region");
        }
        if (validExpansionTargetsFrom(state, player, Set.of(town)).isEmpty()) {
            throw new IllegalArgumentException(
                    "Cannot initialize in " + town.getName()
                    + ": no reachable expansion targets from this starting position");
        }
        placeInSlot(state, town, 0, player);
        state.getSupply().merge(player, -1, Integer::sum);

        // Advance init order backwards
        int next = state.getCurrentPlayerIndex() - 1;
        if (next < 0) {
            state.setInitialization(false);
            state.setCurrentPlayerIndex(0);
            state.setRound(1);
        } else {
            state.setCurrentPlayerIndex(next);
        }
        return 0;
    }

    // -------------------------------------------------------------------------
    // Normal turn
    // -------------------------------------------------------------------------

    private int applyNormalDraw(GameState state, PlayerColor player, DrawRecord draw,
                                 List<String> log, List<InfluenceEvent> events) {
        DrawCostSummary costs = validateAndCalculateCosts(state, player, draw);
        return applyNormalDraw(state, player, draw, log, events, costs);
    }

    private int applyNormalDraw(GameState state, PlayerColor player, DrawRecord draw,
                                 List<String> log, List<InfluenceEvent> events,
                                 DrawCostSummary costs) {
        BonusTileUsage bonus = draw.getBonusTileUsage();
        List<City> extensions = draw.getExtension() != null ? draw.getExtension() : List.of();
        List<City> increases = draw.getIncrease() != null ? draw.getIncrease() : List.of();

        // --- All validation passed; now mutate state ---

        Score score = state.getScores().get(player);
        if (bonus != null) {
            score.setBonusTiles(score.getBonusTiles() - 1);
            if (bonus == BonusTileUsage.MONEY) {
                score.setMoney(score.getMoney() + 10);
            }
        }

        // Phase 1: Income
        score.setMoney(score.getMoney() + costs.getIncome());
        score.setIncome(costs.getIncome());

        // Phase 2: Pay expansion route costs
        for (CityCost extensionCost : costs.getExtensionCosts()) {
            score.setMoney(score.getMoney() - extensionCost.getCost());
        }

        // Phase 3: Pay for increases
        if (bonus == BonusTileUsage.INCREASE) {
            // Double-increase: pay $1 for first branch, second is free; use 2 supply
            score.setMoney(score.getMoney() - 1);
            state.getSupply().merge(player, -2, Integer::sum);
        } else {
            for (City city : increases) {
                score.setMoney(score.getMoney() - 1);
                state.getSupply().merge(player, -1, Integer::sum);
            }
        }

        // Phase 4A: Place expansion branches clockwise
        for (City target : extensions) {
            placeNextClockwise(state, player, target, log, events);
            state.getSupply().merge(player, -1, Integer::sum);
        }

        // Phase 4B: Place increase branches clockwise
        if (bonus == BonusTileUsage.INCREASE && !increases.isEmpty()) {
            // Double-increase: place 2 branches in the same city (supply already decremented)
            placeNextClockwise(state, player, increases.get(0), log, events);
            placeNextClockwise(state, player, increases.get(0), log, events);
        } else {
            for (City city : increases) {
                placeNextClockwise(state, player, city, log, events);
                // supply already decremented in Phase 3
            }
        }

        // Phase 5: Region scoring
        checkAllRegions(state, player, log, events);

        // Game end check (region track reached red zone)
        if (state.getRegionTrackIndex() >= RED_ZONE_INDEX) {
            doFinalScoring(state, log, events);
            state.setEnd(true);
        }

        // Advance turn
        state.setCurrentPlayerIndex(
                (state.getCurrentPlayerIndex() + 1) % state.getPlayers().size());
        state.setRound(state.getRound() + 1);

        return costs.getIncome();
    }

    private void checkDrawLimitLoss(GameState state, PlayerColor player) {
        if (state.isEnd() || state.getDrawHistory().size() < MAX_DRAWS_PER_GAME) {
            return;
        }

        int lowestOtherInfluence = state.getScores().entrySet().stream()
                .filter(entry -> entry.getKey() != player)
                .mapToInt(entry -> entry.getValue().getInfluence())
                .min()
                .orElse(0);
        state.getScores().get(player).setInfluence(lowestOtherInfluence - 1);
        state.setEnd(true);
    }

    // -------------------------------------------------------------------------
    // Income
    // -------------------------------------------------------------------------

    public int getCurrentIncome(GameState state, PlayerColor player) {
        return calcIncome(state, player);
    }

    private int calcIncome(GameState state, PlayerColor player) {
        int freeSlots = 0;
        for (City city : City.values()) {
            if (city.getSize() <= 1) continue; // small towns don't count
            if (state.getClosedCities().contains(city)) continue;
            PlayerColor[] slots = state.getCityBranches().get(city);
            boolean presence = false;
            int free = 0;
            for (PlayerColor slot : slots) {
                if (slot == player) presence = true;
                if (slot == null) free++;
            }
            if (presence) freeSlots += free;
        }
        return Rules.calcIncome(state.getPlayers().size(), freeSlots);
    }

    private DrawCostSummary validateAndCalculateCosts(GameState state, PlayerColor player, DrawRecord draw) {
        BonusTileUsage bonus = draw.getBonusTileUsage();
        List<City> extensions = draw.getExtension() != null ? draw.getExtension() : List.of();
        List<City> increases = draw.getIncrease() != null ? draw.getIncrease() : List.of();

        if (bonus != null) {
            if (state.getRound() <= state.getPlayers().size()) {
                throw new IllegalArgumentException("Bonus tiles cannot be used on the first turn");
            }
            if (state.getScores().get(player).getBonusTiles() <= 0) {
                throw new IllegalArgumentException("No bonus tiles remaining");
            }
        }

        if (bonus == BonusTileUsage.EXTENSION) {
            if (extensions.size() != 2) {
                throw new IllegalArgumentException(
                        "EXTENSION bonus tile requires exactly 2 cities to expand to");
            }
        } else if (extensions.size() > 1) {
            throw new IllegalArgumentException(
                    "Cannot expand to more than 1 city/cities per turn without a bonus tile");
        }
        if (extensions.size() == 2 && extensions.get(0).equals(extensions.get(1))) {
            throw new IllegalArgumentException("Cannot expand to the same city twice");
        }

        List<CityCost> extensionCosts = new ArrayList<>();
        for (City target : extensions) {
            OptionalInt routeCost = minExpansionCost(state, player, target);
            if (routeCost.isEmpty()) {
                throw new IllegalArgumentException(
                        "Cannot reach " + target.getName() + " from any occupied city");
            }
            if (!isValidTarget(state, player, target)) {
                throw new IllegalArgumentException(
                        "Cannot expand to " + target.getName()
                                + ": city is closed, full, or you already have a branch there");
            }
            extensionCosts.add(new CityCost(target, routeCost.getAsInt()));
        }

        if (bonus == BonusTileUsage.INCREASE) {
            if (increases.size() != 2 || !increases.get(0).equals(increases.get(1))) {
                throw new IllegalArgumentException(
                        "INCREASE bonus tile requires exactly 2 identical cities (e.g. [\"CITY\",\"CITY\"])");
            }
            Set<City> validDoubleIncreases = validIncreaseCities(state, player, extensions, 2);
            if (!validDoubleIncreases.contains(increases.get(0))) {
                throw new IllegalArgumentException(
                        "Cannot double-increase in " + increases.get(0).getName()
                                + ": need a pre-existing branch and at least 2 free slots");
            }
        } else {
            Set<City> validIncreases = validIncreaseCities(state, player, extensions);
            for (City city : increases) {
                if (!validIncreases.contains(city)) {
                    throw new IllegalArgumentException(
                            "Cannot increase in " + city.getName()
                                    + ": no pre-existing branch there");
                }
            }
        }

        DrawCostSummary costs = buildNormalDrawCostSummary(state, player, bonus, extensionCosts, increases);
        if (costs.getAvailableMoney() < costs.getTotalCost()) {
            throw new IllegalArgumentException(
                    "Insufficient funds: need $" + costs.getTotalCost()
                            + " but only have $" + costs.getAvailableMoney()
                            + ". " + costs.getCalculation());
        }
        return costs;
    }

    private DrawCostSummary buildInitDrawCostSummary() {
        return new DrawCostSummary(0, 0, 0, 0, List.of(), 0, 0, "Initialization draw: no money spent");
    }

    private DrawCostSummary buildNormalDrawCostSummary(GameState state, PlayerColor player,
                                                       BonusTileUsage bonus, List<CityCost> extensionCosts,
                                                       List<City> increases) {
        int currentMoney = state.getScores().get(player).getMoney();
        int income = calcIncome(state, player);
        int bonusMoney = bonus == BonusTileUsage.MONEY ? 10 : 0;
        int availableMoney = currentMoney + income + bonusMoney;
        int increaseCost = bonus == BonusTileUsage.INCREASE
                ? (increases.isEmpty() ? 0 : 1)
                : increases.size();
        int extensionCost = extensionCosts.stream().mapToInt(CityCost::getCost).sum();
        int totalCost = extensionCost + increaseCost;
        return new DrawCostSummary(currentMoney, income, bonusMoney, availableMoney,
                List.copyOf(extensionCosts), increaseCost, totalCost,
                buildCostCalculation(currentMoney, income, bonusMoney, extensionCosts, increaseCost, totalCost));
    }

    private String buildCostCalculation(int currentMoney, int income, int bonusMoney,
                                        List<CityCost> extensionCosts, int increaseCost, int totalCost) {
        List<String> costParts = new ArrayList<>();
        if (extensionCosts.isEmpty()) {
            costParts.add("no extension costs");
        } else {
            costParts.addAll(extensionCosts.stream()
                    .map(cost -> cost.getCity().name() + ":$" + cost.getCost())
                    .toList());
        }
        if (increaseCost > 0) {
            costParts.add("increase:$" + increaseCost);
        } else if (extensionCosts.isEmpty()) {
            costParts.add("no increase cost");
        }
        return "Available = money:$" + currentMoney
                + " + income:$" + income
                + (bonusMoney > 0 ? " + bonus:$" + bonusMoney : "")
                + " = $" + (currentMoney + income + bonusMoney)
                + "; Costs = " + String.join(" + ", costParts)
                + " = $" + totalCost;
    }

    // -------------------------------------------------------------------------
    // Branch placement
    // -------------------------------------------------------------------------

    private void placeInSlot(GameState state, City city, int slot, PlayerColor player) {
        state.getCityBranches().get(city)[slot] = player;
        if (city.getSize() == 1) {
            state.getClosedCities().add(city);
        }
    }

    private void placeNextClockwise(GameState state, PlayerColor player, City city,
                                     List<String> log, List<InfluenceEvent> events) {
        PlayerColor[] slots = state.getCityBranches().get(city);
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                slots[i] = player;
                if (city.getSize() == 1) {
                    state.getClosedCities().add(city);
                }
                checkCityScoring(state, city, player, log, events);
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // City scoring
    // -------------------------------------------------------------------------

    private void checkCityScoring(GameState state, City city, PlayerColor trigger,
                                  List<String> log,
                                  List<InfluenceEvent> events) {
        if (city.getSize() <= 1) return; // towns score only at game end via doFinalScoring
        if (state.getClosedCities().contains(city)) return;

        PlayerColor[] slots = state.getCityBranches().get(city);
        int total = slots.length;
        int filled = 0;
        Map<PlayerColor, Integer> counts = new EnumMap<>(PlayerColor.class);

        for (PlayerColor slot : slots) {
            if (slot != null) {
                counts.merge(slot, 1, Integer::sum);
                filled++;
            }
        }

        // Check for absolute majority (> half of slots)
        PlayerColor majority = null;
        for (Map.Entry<PlayerColor, Integer> e : counts.entrySet()) {
            if (e.getValue() * 2 > total) {
                majority = e.getKey();
                break;
            }
        }

        boolean full = filled == total;
        if (majority == null && !full) return;

        scoreCity(state, city, trigger, majority, counts, slots, log, events);
    }

    private void scoreCity(GameState state, City city, PlayerColor trigger,
                            PlayerColor majority,
                            Map<PlayerColor, Integer> counts, PlayerColor[] slots,
                            List<String> log, List<InfluenceEvent> events) {
        int cityValue = city.getSize();
        PlayerColor winner;
        int influence;

        if (majority != null) {
            winner = majority;
            influence = cityValue;
        } else {
            // Most branches; tiebreak by earliest clockwise slot
            int max = counts.values().stream().max(Integer::compareTo).orElse(0);
            List<PlayerColor> tied = counts.entrySet().stream()
                    .filter(e -> e.getValue() == max)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

            if (tied.size() == 1) {
                winner = tied.get(0);
            } else {
                winner = Arrays.stream(slots)
                        .filter(s -> s != null && tied.contains(s))
                        .findFirst()
                        .orElse(tied.get(0));
            }
            influence = cityValue / 2;
        }

        awardInfluence(state, winner, influence, "City " + city.getName(), log, events);

        // Winner keeps exactly 1 branch in city (rest returned to supply); all other branches stay
        int winnerReturn = counts.getOrDefault(winner, 0) - 1;
        if (winnerReturn > 0) {
            state.getSupply().merge(winner, winnerReturn, Integer::sum);
            int kept = 0;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == winner) {
                    if (kept == 0) {
                        kept++;
                    } else {
                        slots[i] = null;
                    }
                }
            }
        }

        // Mark city scored
        state.getClosedCities().add(city);

        // First city scored in this region?
        Region region = regionOf(city);
        if (region != null && !state.getRegionFirstScorer().containsKey(region)) {
            state.getRegionFirstScorer().put(region, trigger);
            state.getSupply().merge(trigger, -1, Integer::sum); // branch next to region tile
        }
    }

    // -------------------------------------------------------------------------
    // Region scoring
    // -------------------------------------------------------------------------

    private void checkAllRegions(GameState state, PlayerColor trigger, List<String> log,
                                 List<InfluenceEvent> events) {
        for (Region region : Region.values()) {
            if (state.getClosedRegions().contains(region)) continue;

            Set<City> cities = region.getCities();
            boolean allTowns = cities.stream()
                    .filter(c -> c.getSize() == 1)
                    .allMatch(t -> state.getCityBranches().get(t)[0] != null);
            if (!allTowns) continue;

            boolean allCitiesScored = cities.stream()
                    .filter(c -> c.getSize() > 1)
                    .allMatch(c -> state.getClosedCities().contains(c));
            if (!allCitiesScored) continue;

            scoreRegion(state, region, trigger, log, events);
        }
    }

    private void scoreRegion(GameState state, Region region, PlayerColor trigger,
                              List<String> log, List<InfluenceEvent> events) {
        // Count branches per player across all cities in region
        Map<PlayerColor, Integer> counts = new EnumMap<>(PlayerColor.class);
        for (City city : region.getCities()) {
            for (PlayerColor slot : state.getCityBranches().get(city)) {
                if (slot != null) counts.merge(slot, 1, Integer::sum);
            }
        }

        // Sort by branch count descending
        List<Map.Entry<PlayerColor, Integer>> sorted = counts.entrySet().stream()
                .sorted(Map.Entry.<PlayerColor, Integer>comparingByValue().reversed())
                .collect(Collectors.toList());

        // Tiebreaker: player with branch next to region tile
        PlayerColor tieBreaker = state.getRegionFirstScorer().get(region);
        int playerCount = state.getPlayers().size();
        java.util.Set<PlayerColor> awardedPlayers = new java.util.LinkedHashSet<>();

        PlayerColor firstPlace = awardRegionRank(state, region, sorted, 0, 1, tieBreaker, log, events);
        if (firstPlace != null) {
            awardedPlayers.add(firstPlace);
        }
        if (playerCount > 2) {
            PlayerColor secondPlace = awardRegionRank(state, region, sorted, 1, 2, tieBreaker, log, events);
            if (secondPlace != null) {
                awardedPlayers.add(secondPlace);
            }
        }
        // Third value for all others with >= 1 branch
        int thirdValue = region.getByProfitLevel(3);
        for (Map.Entry<PlayerColor, Integer> e : sorted) {
            PlayerColor p = e.getKey();
            if (awardedPlayers.contains(p)) continue;
            if (e.getValue() > 0) {
                awardInfluence(state, p, thirdValue, region.getName() + " (3rd)", log, events);
            }
        }

        // Region track bonus for triggering player
        List<Integer> track = Region.getRegionFinishInfluence();
        if (state.getRegionTrackIndex() < track.size()) {
            int bonus = track.get(state.getRegionTrackIndex());
            if (bonus > 0) {
                awardInfluence(state, trigger, bonus, "Region track bonus", log, events);
            }
        }

        state.setRegionTrackIndex(state.getRegionTrackIndex() + 1);
        state.getClosedRegions().add(region);
        log.add("Region " + region.getName() + " scored");
    }

    private PlayerColor awardRegionRank(GameState state, Region region,
                                        List<Map.Entry<PlayerColor, Integer>> sorted,
                                        int rank, int profitLevel,
                                        PlayerColor tieBreaker, List<String> log,
                                        List<InfluenceEvent> events) {
        if (sorted.size() <= rank) return null;
        PlayerColor winner = resolveRankWinner(sorted, rank, tieBreaker, state.getPlayers());
        if (winner == null) return null;
        int points = region.getByProfitLevel(profitLevel);
        awardInfluence(state, winner, points,
                region.getName() + " (" + (rank == 0 ? "1st" : "2nd") + ")", log, events);
        return winner;
    }

    private PlayerColor resolveRankWinner(List<Map.Entry<PlayerColor, Integer>> sorted,
                                           int rank, PlayerColor tieBreaker,
                                           List<PlayerColor> turnOrder) {
        if (rank >= sorted.size()) return null;
        int rankValue = sorted.get(rank).getValue();
        List<PlayerColor> tied = sorted.stream()
                .filter(e -> e.getValue() == rankValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (tied.size() == 1) return tied.get(0);
        // Tiebreak: player with branch next to region tile wins directly
        if (tieBreaker != null && tied.contains(tieBreaker)) return tieBreaker;
        // Fallback: tied player closest clockwise to the tieBreaker in turn order
        if (tieBreaker != null) {
            int start = turnOrder.indexOf(tieBreaker);
            if (start >= 0) {
                for (int i = 1; i < turnOrder.size(); i++) {
                    PlayerColor candidate = turnOrder.get((start + i) % turnOrder.size());
                    if (tied.contains(candidate)) return candidate;
                }
            }
        }
        return tied.get(0);
    }

    // -------------------------------------------------------------------------
    // Final scoring
    // -------------------------------------------------------------------------

    private void doFinalScoring(GameState state, List<String> log, List<InfluenceEvent> events) {
        // +1 per branch in small town (skip neutral-color fillers used for inactive regions)
        for (City town : City.getTowns()) {
            PlayerColor occupant = state.getCityBranches().get(town)[0];
            if (occupant != null && state.getScores().containsKey(occupant)) {
                awardInfluence(state, occupant, 1, "Small town " + town.getName(), log, events);
            }
        }
        // +1 per $3
        for (Map.Entry<PlayerColor, Score> entry : state.getScores().entrySet()) {
            int points = entry.getValue().getMoney() / 3;
            if (points > 0) {
                awardInfluence(state, entry.getKey(), points, "Cash conversion", log, events);
            }
        }
        // +4 per unused bonus tile
        for (Map.Entry<PlayerColor, Score> entry : state.getScores().entrySet()) {
            int points = entry.getValue().getBonusTiles() * 4;
            if (points > 0) {
                awardInfluence(state, entry.getKey(), points, "Unused bonus tiles", log, events);
            }
        }
    }

    public List<PlayerColor> getWinners(GameState state) {
        int best = state.getScores().values().stream()
                .mapToInt(Score::getInfluence)
                .max()
                .orElse(Integer.MIN_VALUE);
        return state.getScores().entrySet().stream()
                .filter(e -> e.getValue().getInfluence() == best)
                .map(Map.Entry::getKey)
                .toList();
    }

    private void awardInfluence(GameState state, PlayerColor player, int points, String reason,
                                List<String> log, List<InfluenceEvent> events) {
        state.getScores().get(player).setInfluence(
                state.getScores().get(player).getInfluence() + points);
        log.add(player.name() + " scores " + points + " in " + reason);
        events.add(new InfluenceEvent(state.getRound(), player, points, reason));
    }

    // -------------------------------------------------------------------------
    // Expansion helpers
    // -------------------------------------------------------------------------

    private Set<City> validIncreaseCities(GameState state, PlayerColor player,
                                           List<City> extensions) {
        return validIncreaseCities(state, player, extensions, 1);
    }

    private Set<City> validIncreaseCities(GameState state, PlayerColor player,
                                           List<City> extensions, int minFreeSlots) {
        Set<City> result = new HashSet<>();
        for (City city : City.values()) {
            if (city.getSize() <= 1) continue;              // small towns have no slots
            if (state.getClosedCities().contains(city)) continue;
            if (extensions.contains(city)) continue;        // expansion marker ≠ branch
            PlayerColor[] slots = state.getCityBranches().get(city);
            boolean hasExistingBranch = false;
            int freeSlots = 0;
            int playerBranches = 0;
            for (PlayerColor slot : slots) {
                if (slot == player) { hasExistingBranch = true; playerBranches++; }
                if (slot == null) freeSlots++;
            }
            if (!hasExistingBranch || freeSlots < minFreeSlots) continue;
            // For double-INCREASE: first placement must not trigger absolute majority,
            // otherwise the city scores and the second placement is impossible.
            if (minFreeSlots >= 2 && (playerBranches + 1) * 2 > city.getSize()) continue;
            result.add(city);
        }
        return result;
    }

    private Set<City> validExpansionTargets(GameState state, PlayerColor player) {
        return validExpansionTargetsFrom(state, player, citiesWithPresence(state, player));
    }

    private Set<City> validExpansionTargetsFrom(GameState state, PlayerColor player, Set<City> myCities) {
        Set<City> targets = new HashSet<>();
        for (Connection conn : Rules.CONNECTIONS) {
            City[] pair = conn.cities().toArray(new City[0]);
            for (int i = 0; i < 2; i++) {
                City from = pair[i];
                City to = pair[1 - i];
                if (myCities.contains(from) && isValidTarget(state, player, to)) {
                    targets.add(to);
                }
            }
        }
        return targets;
    }

    // Pre-compute minimum expansion cost for each target city given already-computed myCities.
    private Map<City, Integer> expansionCostMap(Set<City> myCities, Set<City> targets) {
        Map<City, Integer> costs = new EnumMap<>(City.class);
        for (City target : targets) {
            int minCost = Rules.CONNECTIONS.stream()
                    .filter(c -> c.cities().contains(target))
                    .filter(c -> c.cities().stream().anyMatch(city -> city != target && myCities.contains(city)))
                    .mapToInt(Connection::cost)
                    .min()
                    .orElse(0);
            costs.put(target, minCost);
        }
        return costs;
    }

    private boolean isValidTarget(GameState state, PlayerColor player, City target) {
        if (state.getClosedCities().contains(target)) return false;
        PlayerColor[] slots = state.getCityBranches().get(target);
        if (target.getSize() == 1) return slots[0] == null;
        boolean hasPresence = false;
        boolean hasFree = false;
        for (PlayerColor s : slots) {
            if (s == player) hasPresence = true;
            if (s == null) hasFree = true;
        }
        return !hasPresence && hasFree;
    }

    private OptionalInt minExpansionCost(GameState state, PlayerColor player, City target) {
        Set<City> myCities = citiesWithPresence(state, player);
        return Rules.CONNECTIONS.stream()
                .filter(c -> c.cities().contains(target))
                .filter(c -> c.cities().stream().anyMatch(
                        city -> city != target && myCities.contains(city)))
                .mapToInt(Connection::cost)
                .min();
    }

    public Map<City, Integer> computeExpansionCosts(String gameId) {
        return computeExpansionCosts(getGame(gameId));
    }

    public Map<City, Integer> computeExpansionCosts(GameState state) {
        PlayerColor player = currentPlayer(state);
        Set<City> myCities = citiesWithPresence(state, player);
        Map<City, Integer> costs = new EnumMap<>(City.class);
        for (City city : City.values()) {
            OptionalInt cost = Rules.CONNECTIONS.stream()
                    .filter(c -> c.cities().contains(city))
                    .filter(c -> c.cities().stream().anyMatch(
                            other -> other != city && myCities.contains(other)))
                    .mapToInt(Connection::cost)
                    .min();
            costs.put(city, cost.orElse(-1));
        }
        return costs;
    }

    public List<GameState> replayGame(String gameId, boolean withBoards) {
        GameState current = getGame(gameId);
        List<DrawRecord> history = new ArrayList<>(current.getDrawHistory());
        if (!withBoards || history.isEmpty()) {
            return List.of();
        }
        GameState state = buildInitialState(gameId, current.getPlayers());
        List<GameState> snapshots = new ArrayList<>(history.size());
        for (DrawRecord draw : history) {
            applyDrawToState(state, draw);
            snapshots.add(state.deepCopy());
        }
        return snapshots;
    }

    private Set<City> citiesWithPresence(GameState state, PlayerColor player) {
        Set<City> result = new HashSet<>();
        for (City city : City.values()) {
            for (PlayerColor slot : state.getCityBranches().get(city)) {
                if (slot == player) {
                    result.add(city);
                    break;
                }
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private PlayerColor currentPlayer(GameState state) {
        return state.getPlayers().get(state.getCurrentPlayerIndex());
    }

    private Region regionOf(City city) {
        for (Region r : Region.values()) {
            if (r.getCities().contains(city)) return r;
        }
        return null;
    }

    private DrawRecord draw(PlayerColor color, List<City> ext, List<City> inc,
                             BonusTileUsage bonus) {
        DrawRecord d = new DrawRecord();
        d.setColor(color);
        d.setExtension(ext);
        d.setIncrease(inc);
        d.setBonusTileUsage(bonus);
        return d;
    }
}

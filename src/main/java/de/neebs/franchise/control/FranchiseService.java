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
    private static final int RED_ZONE_INDEX = 8; // track indices 8-9 are red zone

    private final Map<String, GameState> games = new ConcurrentHashMap<>();

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
        GameState state = getGame(gameId);
        PlayerColor player = currentPlayer(state);

        if (draw.getColor() != player) {
            throw new IllegalArgumentException("Not your turn");
        }

        List<String> influenceLog = new ArrayList<>();
        int income = 0;

        if (state.isInitialization()) {
            income = applyInitDraw(state, player, draw);
        } else {
            income = applyNormalDraw(state, player, draw, influenceLog);
        }

        state.getDrawHistory().add(draw);
        return new DrawResult(draw, income, influenceLog);
    }

    public List<DrawRecord> getPossibleDraws(String gameId) {
        GameState state = getGame(gameId);
        PlayerColor player = currentPlayer(state);
        List<DrawRecord> draws = new ArrayList<>();

        if (state.isInitialization()) {
            for (City town : City.getTowns()) {
                if (state.getCityBranches().get(town)[0] == null) {
                    draws.add(draw(player, List.of(town), List.of(), null));
                }
            }
            return draws;
        }

        Score score = state.getScores().get(player);
        int income = calcIncome(state, player);
        int availableMoney = score.getMoney() + income;
        boolean bonusEligible = state.getRound() > state.getPlayers().size()
                && score.getBonusTiles() > 0;

        Set<City> expansionTargets = validExpansionTargets(state, player);
        Set<City> increaseCities = validIncreaseCities(state, player, List.of());

        // --- No bonus tile ---

        // Skip (always available)
        draws.add(draw(player, List.of(), List.of(), null));

        // Single extension (+ optional single increase)
        for (City ext : expansionTargets) {
            int extCost = minExpansionCost(state, player, ext).getAsInt();
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
                int extCost = minExpansionCost(state, player, ext).getAsInt();
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
                    int cost1 = minExpansionCost(state, player, ext1).getAsInt();
                    int cost2 = minExpansionCost(state, player, ext2).getAsInt();
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
                        int extCost = minExpansionCost(state, player, ext).getAsInt();
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
        state.setRegionTrackIndex(0);
        state.setDrawHistory(new ArrayList<>());

        // 2- or 3-player adjustment: three fixed regions are inactive
        List<Region> inactive = new ArrayList<>();
        if (players.size() <= 3) {
            inactive = List.of(Region.CALIFORNIA, Region.GRAND_CANYON, Region.MONTANA);
            PlayerColor neutralColor = findUnusedColor(players);
            applyInactiveRegions(state, inactive, neutralColor);
        }
        state.setInactiveRegions(new ArrayList<>(inactive));

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
                                 List<String> log) {
        BonusTileUsage bonus = draw.getBonusTileUsage();
        List<City> extensions = draw.getExtension() != null ? draw.getExtension() : List.of();
        List<City> increases = draw.getIncrease() != null ? draw.getIncrease() : List.of();

        // --- Validate everything before mutating state ---

        if (bonus != null) {
            if (state.getRound() <= state.getPlayers().size()) {
                throw new IllegalArgumentException("Bonus tiles cannot be used on the first turn");
            }
            if (state.getScores().get(player).getBonusTiles() <= 0) {
                throw new IllegalArgumentException("No bonus tiles remaining");
            }
        }

        // EXTENSION bonus requires exactly 2 cities; without it at most 1
        if (bonus == BonusTileUsage.EXTENSION) {
            if (extensions.size() != 2) {
                throw new IllegalArgumentException(
                        "EXTENSION bonus tile requires exactly 2 cities to expand to");
            }
        } else {
            if (extensions.size() > 1) {
                throw new IllegalArgumentException(
                        "Cannot expand to more than 1 city/cities per turn without a bonus tile");
            }
        }
        if (extensions.size() == 2 && extensions.get(0).equals(extensions.get(1))) {
            throw new IllegalArgumentException("Cannot expand to the same city twice");
        }

        // Each extension target must be a valid expansion target:
        // directly reachable, not closed, not already occupied by this player
        for (City target : extensions) {
            if (minExpansionCost(state, player, target).isEmpty()) {
                throw new IllegalArgumentException(
                        "Cannot reach " + target.getName() + " from any occupied city");
            }
            if (!isValidTarget(state, player, target)) {
                throw new IllegalArgumentException(
                        "Cannot expand to " + target.getName()
                        + ": city is closed, full, or you already have a branch there");
            }
        }

        // INCREASE bonus: double-increase in one city — must be represented as [city, city]
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
            // Can only increase in cities with a pre-existing branch,
            // not in cities being expanded to this same turn (expansion marker ≠ branch)
            Set<City> validIncreases = validIncreaseCities(state, player, extensions);
            for (City city : increases) {
                if (!validIncreases.contains(city)) {
                    throw new IllegalArgumentException(
                            "Cannot increase in " + city.getName()
                            + ": no pre-existing branch there");
                }
            }
        }

        // Affordability check: income + current money (+ $10 if MONEY bonus) must cover all costs
        int income = calcIncome(state, player);
        int availableMoney = state.getScores().get(player).getMoney() + income
                + (bonus == BonusTileUsage.MONEY ? 10 : 0);
        int extensionCost = extensions.stream()
                .mapToInt(t -> minExpansionCost(state, player, t).getAsInt())
                .sum();
        int increaseCost = (bonus == BonusTileUsage.INCREASE) ? 1 : increases.size();
        if (availableMoney < extensionCost + increaseCost) {
            throw new IllegalArgumentException(
                    "Insufficient funds: need $" + (extensionCost + increaseCost)
                    + " but only have $" + availableMoney + " (money + income"
                    + (bonus == BonusTileUsage.MONEY ? " + $10 bonus" : "") + ")");
        }

        // --- All validation passed; now mutate state ---

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

        // Phase 2: Pay expansion route costs
        for (City target : extensions) {
            int cost = minExpansionCost(state, player, target).getAsInt();
            score.setMoney(score.getMoney() - cost);
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
            placeNextClockwise(state, player, target, log);
            state.getSupply().merge(player, -1, Integer::sum);
        }

        // Phase 4B: Place increase branches clockwise
        if (bonus == BonusTileUsage.INCREASE && !increases.isEmpty()) {
            // Double-increase: place 2 branches in the same city (supply already decremented)
            placeNextClockwise(state, player, increases.get(0), log);
            placeNextClockwise(state, player, increases.get(0), log);
        } else {
            for (City city : increases) {
                placeNextClockwise(state, player, city, log);
                // supply already decremented in Phase 3
            }
        }

        // Phase 5: Region scoring
        checkAllRegions(state, player, log);

        // Game end check
        if (state.getRegionTrackIndex() >= RED_ZONE_INDEX + 1) {
            doFinalScoring(state);
            state.setEnd(true);
        }

        // Advance turn
        state.setCurrentPlayerIndex(
                (state.getCurrentPlayerIndex() + 1) % state.getPlayers().size());
        state.setRound(state.getRound() + 1);

        return income;
    }

    // -------------------------------------------------------------------------
    // Income
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Branch placement
    // -------------------------------------------------------------------------

    private void placeInSlot(GameState state, City city, int slot, PlayerColor player) {
        state.getCityBranches().get(city)[slot] = player;
    }

    private void placeNextClockwise(GameState state, PlayerColor player, City city,
                                     List<String> log) {
        PlayerColor[] slots = state.getCityBranches().get(city);
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) {
                slots[i] = player;
                checkCityScoring(state, city, log);
                return;
            }
        }
    }

    // -------------------------------------------------------------------------
    // City scoring
    // -------------------------------------------------------------------------

    private void checkCityScoring(GameState state, City city, List<String> log) {
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

        scoreCity(state, city, majority, counts, slots, log);
    }

    private void scoreCity(GameState state, City city, PlayerColor majority,
                            Map<PlayerColor, Integer> counts, PlayerColor[] slots,
                            List<String> log) {
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

        state.getScores().get(winner).setInfluence(
                state.getScores().get(winner).getInfluence() + influence);
        log.add(winner.name() + " scores " + influence + " in " + city.getName());

        // Return branches to supply: winner keeps 1, others keep none
        for (Map.Entry<PlayerColor, Integer> e : counts.entrySet()) {
            int returnCount = e.getKey() == winner ? e.getValue() - 1 : e.getValue();
            if (returnCount > 0) {
                state.getSupply().merge(e.getKey(), returnCount, Integer::sum);
            }
        }

        // Mark city scored
        state.getClosedCities().add(city);

        // First city scored in this region?
        Region region = regionOf(city);
        if (region != null && !state.getRegionFirstScorer().containsKey(region)) {
            state.getRegionFirstScorer().put(region, winner);
            state.getSupply().merge(winner, -1, Integer::sum); // branch next to region tile
        }
    }

    // -------------------------------------------------------------------------
    // Region scoring
    // -------------------------------------------------------------------------

    private void checkAllRegions(GameState state, PlayerColor trigger, List<String> log) {
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

            scoreRegion(state, region, trigger, log);
        }
    }

    private void scoreRegion(GameState state, Region region, PlayerColor trigger,
                              List<String> log) {
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

        awardRegionRank(state, region, sorted, 0, 1, tieBreaker, log);
        if (playerCount > 2) {
            awardRegionRank(state, region, sorted, 1, 2, tieBreaker, log);
        }
        // Third value for all others with >= 1 branch
        int thirdValue = region.getByProfitLevel(3);
        for (Map.Entry<PlayerColor, Integer> e : sorted) {
            PlayerColor p = e.getKey();
            // skip 1st and 2nd place players
            if (isRank(sorted, p, 0, tieBreaker) || isRank(sorted, p, 1, tieBreaker)) continue;
            if (e.getValue() > 0) {
                state.getScores().get(p).setInfluence(
                        state.getScores().get(p).getInfluence() + thirdValue);
                log.add(p.name() + " scores " + thirdValue + " (3rd) in " + region.getName());
            }
        }

        // Region track bonus for triggering player
        List<Integer> track = Region.getRegionFinishInfluence();
        if (state.getRegionTrackIndex() < track.size()) {
            int bonus = track.get(state.getRegionTrackIndex());
            if (bonus > 0) {
                state.getScores().get(trigger).setInfluence(
                        state.getScores().get(trigger).getInfluence() + bonus);
                log.add(trigger.name() + " scores " + bonus + " track bonus");
            }
        }

        state.setRegionTrackIndex(state.getRegionTrackIndex() + 1);
        state.getClosedRegions().add(region);
        log.add("Region " + region.getName() + " scored");
    }

    private void awardRegionRank(GameState state, Region region,
                                  List<Map.Entry<PlayerColor, Integer>> sorted,
                                  int rank, int profitLevel,
                                  PlayerColor tieBreaker, List<String> log) {
        if (sorted.size() <= rank) return;
        PlayerColor winner = resolveRankWinner(sorted, rank, tieBreaker);
        if (winner == null) return;
        int points = region.getByProfitLevel(profitLevel);
        state.getScores().get(winner).setInfluence(
                state.getScores().get(winner).getInfluence() + points);
        log.add(winner.name() + " scores " + points + " (" + (rank == 0 ? "1st" : "2nd")
                + ") in " + region.getName());
    }

    private PlayerColor resolveRankWinner(List<Map.Entry<PlayerColor, Integer>> sorted,
                                           int rank, PlayerColor tieBreaker) {
        if (rank >= sorted.size()) return null;
        int rankValue = sorted.get(rank).getValue();
        // Collect all players tied at this rank value
        List<PlayerColor> tied = sorted.stream()
                .filter(e -> e.getValue() == rankValue)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (tied.size() == 1) return tied.get(0);
        // Tiebreak: player whose branch is next to the region tile wins
        if (tieBreaker != null && tied.contains(tieBreaker)) return tieBreaker;
        return tied.get(0); // fallback
    }

    private boolean isRank(List<Map.Entry<PlayerColor, Integer>> sorted, PlayerColor p,
                             int rank, PlayerColor tieBreaker) {
        return p == resolveRankWinner(sorted, rank, tieBreaker);
    }

    // -------------------------------------------------------------------------
    // Final scoring
    // -------------------------------------------------------------------------

    private void doFinalScoring(GameState state) {
        // +1 per branch in small town
        for (City town : City.getTowns()) {
            PlayerColor occupant = state.getCityBranches().get(town)[0];
            if (occupant != null) {
                state.getScores().get(occupant).setInfluence(
                        state.getScores().get(occupant).getInfluence() + 1);
            }
        }
        // +1 per $3
        for (Score s : state.getScores().values()) {
            s.setInfluence(s.getInfluence() + s.getMoney() / 3);
        }
        // +4 per unused bonus tile
        for (Score s : state.getScores().values()) {
            s.setInfluence(s.getInfluence() + s.getBonusTiles() * 4);
        }
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
            for (PlayerColor slot : slots) {
                if (slot == player) hasExistingBranch = true;
                if (slot == null) freeSlots++;
            }
            if (hasExistingBranch && freeSlots >= minFreeSlots) result.add(city);
        }
        return result;
    }

    private Set<City> validExpansionTargets(GameState state, PlayerColor player) {
        Set<City> myCities = citiesWithPresence(state, player);
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
        GameState state = getGame(gameId);
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

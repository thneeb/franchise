package de.neebs.franchise.entity;

import java.util.*;
import java.util.stream.Collectors;

public class GameState {

    private String id;
    private List<PlayerColor> players;
    private boolean initialization;
    private boolean end;
    private int round;
    private int currentPlayerIndex;
    private Map<PlayerColor, Score> scores;
    private Map<PlayerColor, Integer> supply;
    private Map<City, PlayerColor[]> cityBranches;
    private Set<City> closedCities;
    private List<Region> closedRegions;
    private Map<Region, PlayerColor> regionFirstScorer;
    private int regionTrackIndex;
    private List<Region> inactiveRegions;
    private List<DrawRecord> drawHistory;
    private int consecutiveSkipsWithoutExpansion;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public List<PlayerColor> getPlayers() { return players; }
    public void setPlayers(List<PlayerColor> players) { this.players = players; }

    public boolean isInitialization() { return initialization; }
    public void setInitialization(boolean initialization) { this.initialization = initialization; }

    public boolean isEnd() { return end; }
    public void setEnd(boolean end) { this.end = end; }

    public int getRound() { return round; }
    public void setRound(int round) { this.round = round; }

    public int getCurrentPlayerIndex() { return currentPlayerIndex; }
    public void setCurrentPlayerIndex(int currentPlayerIndex) { this.currentPlayerIndex = currentPlayerIndex; }

    public Map<PlayerColor, Score> getScores() { return scores; }
    public void setScores(Map<PlayerColor, Score> scores) { this.scores = scores; }

    public Map<PlayerColor, Integer> getSupply() { return supply; }
    public void setSupply(Map<PlayerColor, Integer> supply) { this.supply = supply; }

    public Map<City, PlayerColor[]> getCityBranches() { return cityBranches; }
    public void setCityBranches(Map<City, PlayerColor[]> cityBranches) { this.cityBranches = cityBranches; }

    public Set<City> getClosedCities() { return closedCities; }
    public void setClosedCities(Set<City> closedCities) { this.closedCities = closedCities; }

    public List<Region> getClosedRegions() { return closedRegions; }
    public void setClosedRegions(List<Region> closedRegions) { this.closedRegions = closedRegions; }

    public Map<Region, PlayerColor> getRegionFirstScorer() { return regionFirstScorer; }
    public void setRegionFirstScorer(Map<Region, PlayerColor> regionFirstScorer) { this.regionFirstScorer = regionFirstScorer; }

    public int getRegionTrackIndex() { return regionTrackIndex; }
    public void setRegionTrackIndex(int regionTrackIndex) { this.regionTrackIndex = regionTrackIndex; }

    public List<Region> getInactiveRegions() { return inactiveRegions; }
    public void setInactiveRegions(List<Region> inactiveRegions) { this.inactiveRegions = inactiveRegions; }

    public List<DrawRecord> getDrawHistory() { return drawHistory; }
    public void setDrawHistory(List<DrawRecord> drawHistory) { this.drawHistory = drawHistory; }

    public int getConsecutiveSkipsWithoutExpansion() { return consecutiveSkipsWithoutExpansion; }
    public void setConsecutiveSkipsWithoutExpansion(int consecutiveSkipsWithoutExpansion) {
        this.consecutiveSkipsWithoutExpansion = consecutiveSkipsWithoutExpansion;
    }

    public GameState deepCopy() {
        GameState copy = new GameState();
        copy.id = this.id;
        copy.initialization = this.initialization;
        copy.end = this.end;
        copy.round = this.round;
        copy.currentPlayerIndex = this.currentPlayerIndex;
        copy.regionTrackIndex = this.regionTrackIndex;
        copy.consecutiveSkipsWithoutExpansion = this.consecutiveSkipsWithoutExpansion;
        copy.players = new ArrayList<>(this.players);
        copy.inactiveRegions = new ArrayList<>(this.inactiveRegions);
        copy.closedRegions = new ArrayList<>(this.closedRegions);
        copy.closedCities = new HashSet<>(this.closedCities);
        copy.regionFirstScorer = new EnumMap<>(this.regionFirstScorer);

        copy.scores = this.scores.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().copy(),
                        (a, b) -> a, () -> new EnumMap<>(PlayerColor.class)));

        copy.supply = new EnumMap<>(this.supply);

        Map<City, PlayerColor[]> branchesCopy = new EnumMap<>(City.class);
        this.cityBranches.forEach((city, arr) -> branchesCopy.put(city, arr.clone()));
        copy.cityBranches = branchesCopy;

        // DrawRecord fields are set once and never mutated — shallow copy is safe
        copy.drawHistory = new ArrayList<>(this.drawHistory);
        return copy;
    }
}

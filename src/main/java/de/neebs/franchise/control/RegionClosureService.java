package de.neebs.franchise.control;

import de.neebs.franchise.entity.City;
import de.neebs.franchise.entity.Connection;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import de.neebs.franchise.entity.Region;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Computes how many extends each player needs to close each active region,
 * and returns the ordered sequence of cities to extend to.
 */
@Service
public class RegionClosureService {

    private static final Map<City, Set<City>> ADJACENCY = buildAdjacency();

    private static Map<City, Set<City>> buildAdjacency() {
        Map<City, Set<City>> map = new EnumMap<>(City.class);
        for (Connection conn : Rules.CONNECTIONS) {
            List<City> pair = new ArrayList<>(conn.cities());
            map.computeIfAbsent(pair.get(0), k -> EnumSet.noneOf(City.class)).add(pair.get(1));
            map.computeIfAbsent(pair.get(1), k -> EnumSet.noneOf(City.class)).add(pair.get(0));
        }
        return Collections.unmodifiableMap(map);
    }

    public List<RegionClosureInfo> analyze(GameState state) {
        List<PlayerColor> activePlayers = state.getPlayers();

        List<RegionClosureInfo> result = new ArrayList<>();
        for (Region region : Region.values()) {
            if (state.getInactiveRegions().contains(region)) continue;
            if (state.getClosedRegions().contains(region)) continue;

            List<City> openCities = region.getCities().stream()
                    .filter(city -> !isEntered(city, state))
                    .sorted(Comparator.comparing(Enum::name))
                    .collect(Collectors.toList());

            Map<PlayerColor, ClosurePlayerInfo> byPlayer = new LinkedHashMap<>();
            for (PlayerColor player : activePlayers) {
                Set<City> network = getPlayerNetwork(player, state);
                int branches = countBranches(player, region, state);
                int maxBranches = activePlayers.stream()
                        .mapToInt(p -> countBranches(p, region, state))
                        .max().orElse(0);
                boolean leads = branches > 0 && branches == maxBranches
                        && activePlayers.stream().filter(p -> p != player)
                        .allMatch(p -> countBranches(p, region, state) < branches);
                List<City> path = computeClosingPath(network, openCities);
                byPlayer.put(player, new ClosurePlayerInfo(branches, leads, path.size(), path));
            }

            result.add(new RegionClosureInfo(region, openCities, byPlayer));
        }
        return result;
    }

    private List<City> computeClosingPath(Set<City> startNetwork, List<City> openCities) {
        if (openCities.isEmpty()) return List.of();

        Set<City> network = new HashSet<>(startNetwork);
        Set<City> remaining = new LinkedHashSet<>(openCities);
        List<City> path = new ArrayList<>();

        while (!remaining.isEmpty()) {
            Map<City, City> parent = bfs(network);

            // Pick the reachable remaining city closest to the current network
            City closest = remaining.stream()
                    .filter(parent::containsKey)
                    .min(Comparator.comparingInt(c -> pathLength(c, parent)))
                    .orElse(null);

            if (closest == null) break; // disconnected graph — shouldn't happen

            List<City> subPath = reconstructPath(closest, parent);
            path.addAll(subPath);
            network.addAll(subPath);
            remaining.remove(closest);
        }
        return path;
    }

    /** Multi-source BFS from the given network. Returns parent map for non-start cities. */
    private static Map<City, City> bfs(Set<City> startSet) {
        Map<City, City> parent = new HashMap<>();
        Queue<City> queue = new LinkedList<>();
        Set<City> visited = new HashSet<>(startSet);
        queue.addAll(startSet);
        while (!queue.isEmpty()) {
            City current = queue.poll();
            for (City neighbor : ADJACENCY.getOrDefault(current, Set.of())) {
                if (visited.add(neighbor)) {
                    parent.put(neighbor, current);
                    queue.add(neighbor);
                }
            }
        }
        return parent;
    }

    private static List<City> reconstructPath(City target, Map<City, City> parent) {
        List<City> path = new ArrayList<>();
        for (City c = target; parent.containsKey(c); c = parent.get(c)) {
            path.add(0, c);
        }
        return path;
    }

    private static int pathLength(City target, Map<City, City> parent) {
        int len = 0;
        for (City c = target; parent.containsKey(c); c = parent.get(c)) len++;
        return len;
    }

    private static boolean isEntered(City city, GameState state) {
        PlayerColor[] slots = state.getCityBranches().get(city);
        if (slots == null) return false;
        for (PlayerColor slot : slots) {
            if (slot != null) return true;
        }
        return false;
    }

    private static Set<City> getPlayerNetwork(PlayerColor player, GameState state) {
        Set<City> cities = EnumSet.noneOf(City.class);
        for (Map.Entry<City, PlayerColor[]> e : state.getCityBranches().entrySet()) {
            for (PlayerColor slot : e.getValue()) {
                if (slot == player) {
                    cities.add(e.getKey());
                    break;
                }
            }
        }
        return cities;
    }

    private static int countBranches(PlayerColor player, Region region, GameState state) {
        int count = 0;
        for (City city : region.getCities()) {
            PlayerColor[] slots = state.getCityBranches().get(city);
            if (slots == null) continue;
            for (PlayerColor slot : slots) {
                if (slot == player) count++;
            }
        }
        return count;
    }

    // -------------------------------------------------------------------------
    // Value objects
    // -------------------------------------------------------------------------

    public record RegionClosureInfo(
            Region region,
            List<City> openCities,
            Map<PlayerColor, ClosurePlayerInfo> byPlayer) {

        public int openCityCount() { return openCities.size(); }
    }

    public record ClosurePlayerInfo(
            int branches,
            boolean leads,
            int minExtendsToClose,
            List<City> closingPath) {

        // True if closeable in 1 move: 1 extend, or 2 extends with a bonus EXTENSION tile
        public boolean canCloseNextTurn() {
            return minExtendsToClose <= 2;
        }
    }
}

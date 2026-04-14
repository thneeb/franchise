package de.neebs.franchise.control;

import de.neebs.franchise.entity.City;
import de.neebs.franchise.entity.GameState;
import de.neebs.franchise.entity.PlayerColor;
import de.neebs.franchise.entity.Region;
import de.neebs.franchise.entity.Score;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@link GameState} into a fixed-size float feature vector for the value network.
 *
 * <p>The encoding is <b>perspective-normalised</b>: the given {@code perspective} player is
 * always placed at index 0, followed by the other players in turn order. This lets a single
 * network weight set generalise across all player colours.
 *
 * <p>Feature layout for n players (total = 50n + 58):
 * <ol>
 *   <li>City ownership — for each of 45 cities: fraction of slots held by each perspective player (45 × n)</li>
 *   <li>City closed flag — binary per city (45)</li>
 *   <li>Per-player stats in perspective order — money/50, income/20, influence/50, bonusTiles/5, supply/40 (5 × n)</li>
 *   <li>Region track progress — regionTrackIndex / 10 (1)</li>
 *   <li>Closed regions — binary per region (10)</li>
 *   <li>Is perspective player the current mover — binary (1)</li>
 *   <li>Round — round / 100 (1)</li>
 * </ol>
 */
class StateEncoder {

    static int inputSize(int numPlayers) {
        return City.values().length * numPlayers    // city ownership
             + City.values().length                  // city closed
             + 5 * numPlayers                        // per-player stats
             + 1                                     // region track
             + Region.values().length                // closed regions
             + 1                                     // is perspective player current?
             + 1;                                    // round
    }

    float[] encode(GameState state, PlayerColor perspective) {
        List<PlayerColor> players = state.getPlayers();
        int n = players.size();
        int perspIdx = players.indexOf(perspective);

        // Build perspective-relative player order
        List<PlayerColor> perspOrder = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            perspOrder.add(players.get((perspIdx + i) % n));
        }

        float[] f = new float[inputSize(n)];
        int idx = 0;

        // --- City ownership ---
        for (City city : City.values()) {
            PlayerColor[] slots = state.getCityBranches().get(city);
            int size = city.getSize();
            for (PlayerColor p : perspOrder) {
                int count = 0;
                for (PlayerColor slot : slots) {
                    if (slot == p) count++;
                }
                f[idx++] = (float) count / size;
            }
        }

        // --- City closed flag ---
        for (City city : City.values()) {
            f[idx++] = state.getClosedCities().contains(city) ? 1.0f : 0.0f;
        }

        // --- Per-player stats ---
        for (PlayerColor p : perspOrder) {
            Score s = state.getScores().get(p);
            int supply = state.getSupply().getOrDefault(p, 0);
            f[idx++] = s.getMoney()     / 50.0f;
            f[idx++] = s.getIncome()    / 20.0f;
            f[idx++] = s.getInfluence() / 50.0f;
            f[idx++] = s.getBonusTiles() / 5.0f;
            f[idx++] = supply            / 40.0f;
        }

        // --- Region track ---
        f[idx++] = state.getRegionTrackIndex() / 10.0f;

        // --- Closed regions ---
        for (Region region : Region.values()) {
            f[idx++] = state.getClosedRegions().contains(region) ? 1.0f : 0.0f;
        }

        // --- Is perspective player the current mover? ---
        PlayerColor currentMover = players.get(state.getCurrentPlayerIndex());
        f[idx++] = (currentMover == perspective) ? 1.0f : 0.0f;

        // --- Round ---
        f[idx++] = state.getRound() / 100.0f;

        return f;
    }
}

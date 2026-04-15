package de.neebs.franchise.entity;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class LearningProgress {

    private final String runId;
    private final int gamesTotal;
    private final AtomicInteger gamesCompleted = new AtomicInteger();
    private final Map<PlayerColor, AtomicInteger> wins = new EnumMap<>(PlayerColor.class);
    private volatile boolean done;

    public LearningProgress(String runId, int gamesTotal, List<PlayerColor> players) {
        this.runId = runId;
        this.gamesTotal = gamesTotal;
        players.forEach(player -> wins.put(player, new AtomicInteger()));
    }

    public void increment(PlayerColor winner) {
        AtomicInteger winCounter = wins.get(winner);
        if (winCounter != null) {
            winCounter.incrementAndGet();
        }
        int completed = gamesCompleted.incrementAndGet();
        if (completed >= gamesTotal) {
            done = true;
        }
    }

    public String getRunId() { return runId; }
    public int getGamesTotal() { return gamesTotal; }
    public int getGamesCompleted() { return gamesCompleted.get(); }
    public Map<PlayerColor, Integer> getWins() {
        Map<PlayerColor, Integer> snapshot = new EnumMap<>(PlayerColor.class);
        wins.forEach((player, count) -> snapshot.put(player, count.get()));
        return snapshot;
    }
    public boolean isDone() { return done; }
}

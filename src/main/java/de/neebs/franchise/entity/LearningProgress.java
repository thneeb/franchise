package de.neebs.franchise.entity;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class LearningProgress {

    private final String runId;
    private final int gamesTotal;
    private final AtomicInteger gamesCompleted = new AtomicInteger();
    private final Map<PlayerColor, AtomicInteger> wins = new EnumMap<>(PlayerColor.class);
    private final Map<PlayerColor, AtomicLong> processingTimeNanos = new EnumMap<>(PlayerColor.class);
    private volatile boolean done;

    public LearningProgress(String runId, int gamesTotal, List<PlayerColor> players) {
        this.runId = runId;
        this.gamesTotal = gamesTotal;
        players.forEach(player -> {
            wins.put(player, new AtomicInteger());
            processingTimeNanos.put(player, new AtomicLong());
        });
    }

    public void recordProcessingTime(PlayerColor player, long nanos) {
        AtomicLong processingTime = processingTimeNanos.get(player);
        if (processingTime != null) {
            processingTime.addAndGet(nanos);
        }
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
    public Map<PlayerColor, Long> getProcessingTimeNanos() {
        Map<PlayerColor, Long> snapshot = new EnumMap<>(PlayerColor.class);
        processingTimeNanos.forEach((player, total) -> snapshot.put(player, total.get()));
        return snapshot;
    }
    public boolean isDone() { return done; }
}

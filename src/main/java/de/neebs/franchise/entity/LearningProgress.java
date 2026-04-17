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
    private final AtomicLong snapshotTimeNanos = new AtomicLong();
    private final AtomicLong trainingTimeNanos = new AtomicLong();
    private final AtomicLong modelSaveTimeNanos = new AtomicLong();
    private final Map<String, AtomicLong> trainingRuns = new java.util.LinkedHashMap<>();
    private final long startedAtNanos = System.nanoTime();
    private volatile long completedAtNanos;
    private volatile boolean done;

    public LearningProgress(String runId, int gamesTotal, List<PlayerColor> players, Map<String, Long> initialTrainingRuns) {
        this.runId = runId;
        this.gamesTotal = gamesTotal;
        players.forEach(player -> {
            wins.put(player, new AtomicInteger());
            processingTimeNanos.put(player, new AtomicLong());
        });
        initialTrainingRuns.forEach((strategy, count) -> trainingRuns.put(strategy, new AtomicLong(count)));
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
            completedAtNanos = System.nanoTime();
        }
    }

    public void recordSnapshotTime(long nanos) {
        snapshotTimeNanos.addAndGet(nanos);
    }

    public void recordTrainingTime(long nanos) {
        trainingTimeNanos.addAndGet(nanos);
    }

    public void recordModelSaveTime(long nanos) {
        modelSaveTimeNanos.addAndGet(nanos);
    }

    public void updateTrainingRuns(String strategy, long count) {
        trainingRuns.computeIfAbsent(strategy, ignored -> new AtomicLong()).set(count);
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
    public long getSnapshotTimeNanos() { return snapshotTimeNanos.get(); }
    public long getTrainingTimeNanos() { return trainingTimeNanos.get(); }
    public long getModelSaveTimeNanos() { return modelSaveTimeNanos.get(); }
    public long getTotalTimeNanos() { return done ? completedAtNanos - startedAtNanos : System.nanoTime() - startedAtNanos; }
    public Map<String, Long> getTrainingRuns() {
        Map<String, Long> snapshot = new java.util.LinkedHashMap<>();
        trainingRuns.forEach((strategy, count) -> snapshot.put(strategy, count.get()));
        return snapshot;
    }
    public boolean isDone() { return done; }
}

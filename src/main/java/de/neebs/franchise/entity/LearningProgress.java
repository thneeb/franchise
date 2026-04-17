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
    private final Map<String, TrainingRunCounter> trainingRuns = new java.util.LinkedHashMap<>();
    private final Map<String, String> playerStrategies = new java.util.LinkedHashMap<>();
    private final Map<String, Map<String, Object>> playerParams = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> learningModels = new java.util.LinkedHashSet<>();
    private final long startedAtNanos = System.nanoTime();
    private volatile long completedAtNanos;
    private volatile boolean done;

    public LearningProgress(String runId,
                            int gamesTotal,
                            List<PlayerColor> players,
                            java.util.List<TrainingRunCount> initialTrainingRuns,
                            Map<PlayerColor, String> playerStrategies,
                            Map<PlayerColor, Map<String, Object>> playerParams,
                            java.util.Set<String> learningModels) {
        this.runId = runId;
        this.gamesTotal = gamesTotal;
        players.forEach(player -> {
            wins.put(player, new AtomicInteger());
            processingTimeNanos.put(player, new AtomicLong());
        });
        initialTrainingRuns.forEach(count -> trainingRuns.put(
                trainingRunKey(count.strategy(), count.trainingTarget()),
                new TrainingRunCounter(count.strategy(), count.trainingTarget(), count.value())));
        playerStrategies.forEach((color, strategy) -> this.playerStrategies.put(color.name(), strategy));
        playerParams.forEach((color, params) -> this.playerParams.put(color.name(), new java.util.LinkedHashMap<>(params)));
        this.learningModels.addAll(learningModels);
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

    public void updateTrainingRuns(TrainingRunCount count) {
        trainingRuns.computeIfAbsent(
                        trainingRunKey(count.strategy(), count.trainingTarget()),
                        ignored -> new TrainingRunCounter(count.strategy(), count.trainingTarget(), 0L))
                .value().set(count.value());
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
    public java.util.List<TrainingRunCount> getTrainingRuns() {
        return trainingRuns.values().stream()
                .map(counter -> new TrainingRunCount(
                        counter.strategy(),
                        counter.trainingTarget(),
                        counter.value().get()))
                .toList();
    }
    public Map<String, String> getPlayerStrategies() {
        return new java.util.LinkedHashMap<>(playerStrategies);
    }
    public Map<String, Map<String, Object>> getPlayerParams() {
        Map<String, Map<String, Object>> snapshot = new java.util.LinkedHashMap<>();
        playerParams.forEach((color, params) -> snapshot.put(color, new java.util.LinkedHashMap<>(params)));
        return snapshot;
    }
    public java.util.Set<String> getLearningModels() {
        return new java.util.LinkedHashSet<>(learningModels);
    }
    public boolean isDone() { return done; }

    private static String trainingRunKey(String strategy, String trainingTarget) {
        return strategy + ":" + (trainingTarget != null ? trainingTarget : "");
    }

    private record TrainingRunCounter(String strategy, String trainingTarget, AtomicLong value) {
        private TrainingRunCounter(String strategy, String trainingTarget, long value) {
            this(strategy, trainingTarget, new AtomicLong(value));
        }
    }
}

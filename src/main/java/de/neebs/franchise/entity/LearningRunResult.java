package de.neebs.franchise.entity;

import java.util.EnumMap;
import java.util.Map;

public class LearningRunResult {

    private final Map<PlayerColor, Integer> wins;
    private final Map<PlayerColor, Long> processingTimeNanos;
    private final long snapshotTimeNanos;
    private final long trainingTimeNanos;
    private final long modelSaveTimeNanos;
    private final long totalTimeNanos;
    private final Map<String, Long> trainingRuns;

    public LearningRunResult(Map<PlayerColor, Integer> wins,
                             Map<PlayerColor, Long> processingTimeNanos,
                             long snapshotTimeNanos,
                             long trainingTimeNanos,
                             long modelSaveTimeNanos,
                             long totalTimeNanos,
                             Map<String, Long> trainingRuns) {
        this.wins = new EnumMap<>(wins);
        this.processingTimeNanos = new EnumMap<>(processingTimeNanos);
        this.snapshotTimeNanos = snapshotTimeNanos;
        this.trainingTimeNanos = trainingTimeNanos;
        this.modelSaveTimeNanos = modelSaveTimeNanos;
        this.totalTimeNanos = totalTimeNanos;
        this.trainingRuns = new java.util.LinkedHashMap<>(trainingRuns);
    }

    public Map<PlayerColor, Integer> getWins() {
        return new EnumMap<>(wins);
    }

    public Map<PlayerColor, Long> getProcessingTimeNanos() {
        return new EnumMap<>(processingTimeNanos);
    }

    public long getSnapshotTimeNanos() {
        return snapshotTimeNanos;
    }

    public long getTrainingTimeNanos() {
        return trainingTimeNanos;
    }

    public long getModelSaveTimeNanos() {
        return modelSaveTimeNanos;
    }

    public long getTotalTimeNanos() {
        return totalTimeNanos;
    }

    public Map<String, Long> getTrainingRuns() {
        return new java.util.LinkedHashMap<>(trainingRuns);
    }
}

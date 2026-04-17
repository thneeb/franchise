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
    private final java.util.List<TrainingRunCount> trainingRuns;
    private final Map<String, String> playerStrategies;
    private final Map<String, Map<String, Object>> playerParams;
    private final java.util.Set<String> learningModels;

    public LearningRunResult(Map<PlayerColor, Integer> wins,
                             Map<PlayerColor, Long> processingTimeNanos,
                             long snapshotTimeNanos,
                             long trainingTimeNanos,
                             long modelSaveTimeNanos,
                             long totalTimeNanos,
                             java.util.List<TrainingRunCount> trainingRuns,
                             Map<PlayerColor, String> playerStrategies,
                             Map<PlayerColor, Map<String, Object>> playerParams,
                             java.util.Set<String> learningModels) {
        this.wins = new EnumMap<>(wins);
        this.processingTimeNanos = new EnumMap<>(processingTimeNanos);
        this.snapshotTimeNanos = snapshotTimeNanos;
        this.trainingTimeNanos = trainingTimeNanos;
        this.modelSaveTimeNanos = modelSaveTimeNanos;
        this.totalTimeNanos = totalTimeNanos;
        this.trainingRuns = java.util.List.copyOf(trainingRuns);
        this.playerStrategies = new java.util.LinkedHashMap<>();
        playerStrategies.forEach((color, strategy) -> this.playerStrategies.put(color.name(), strategy));
        this.playerParams = new java.util.LinkedHashMap<>();
        playerParams.forEach((color, params) -> this.playerParams.put(color.name(), new java.util.LinkedHashMap<>(params)));
        this.learningModels = new java.util.LinkedHashSet<>(learningModels);
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

    public java.util.List<TrainingRunCount> getTrainingRuns() {
        return java.util.List.copyOf(trainingRuns);
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
}

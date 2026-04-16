package de.neebs.franchise.entity;

import java.util.EnumMap;
import java.util.Map;

public class LearningRunResult {

    private final Map<PlayerColor, Integer> wins;
    private final Map<PlayerColor, Long> processingTimeNanos;

    public LearningRunResult(Map<PlayerColor, Integer> wins, Map<PlayerColor, Long> processingTimeNanos) {
        this.wins = new EnumMap<>(wins);
        this.processingTimeNanos = new EnumMap<>(processingTimeNanos);
    }

    public Map<PlayerColor, Integer> getWins() {
        return new EnumMap<>(wins);
    }

    public Map<PlayerColor, Long> getProcessingTimeNanos() {
        return new EnumMap<>(processingTimeNanos);
    }
}

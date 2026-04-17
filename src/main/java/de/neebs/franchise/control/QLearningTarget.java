package de.neebs.franchise.control;

import java.util.Locale;
import java.util.Map;

enum QLearningTarget {
    TERMINAL_OUTCOME("terminal-outcome"),
    INFLUENCE("influence");

    private static final String PARAM_NAME = "trainingTarget";

    private final String modelKey;

    QLearningTarget(String modelKey) {
        this.modelKey = modelKey;
    }

    String modelKey() {
        return modelKey;
    }

    static QLearningTarget fromParams(Map<String, Object> params) {
        if (params == null) return TERMINAL_OUTCOME;
        Object raw = params.get(PARAM_NAME);
        if (!(raw instanceof String value) || value.isBlank()) {
            return TERMINAL_OUTCOME;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "INFLUENCE", "INFLUENCE_BASED" -> INFLUENCE;
            case "TERMINAL_OUTCOME", "FINAL_RESULT", "OUTCOME" -> TERMINAL_OUTCOME;
            default -> TERMINAL_OUTCOME;
        };
    }
}

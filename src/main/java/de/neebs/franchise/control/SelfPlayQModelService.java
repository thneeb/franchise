package de.neebs.franchise.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and saves neural networks used by the Q-learning strategy.
 */
@Service
class SelfPlayQModelService {

    private static final String MODEL_DIR = "q-learning/";
    private static final String TERMINAL_OUTCOME_MODEL_VERSION = "d099-v1";
    private static final String INFLUENCE_MODEL_VERSION = "d099-v1";
    private static final int HIDDEN1 = 256;
    private static final int HIDDEN2 = 128;

    private final ObjectMapper objectMapper;
    private final Map<String, NeuralNetwork> cache = new ConcurrentHashMap<>();

    SelfPlayQModelService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    NeuralNetwork getOrCreate(int numPlayers, QLearningTarget trainingTarget) {
        return getOrCreate(numPlayers, trainingTarget, null);
    }

    NeuralNetwork getOrCreate(int numPlayers, QLearningTarget trainingTarget, String variant) {
        return cache.computeIfAbsent(cacheKey(numPlayers, trainingTarget, variant),
                ignored -> loadOrCreate(numPlayers, trainingTarget, variant));
    }

    private NeuralNetwork loadOrCreate(int numPlayers, QLearningTarget trainingTarget, String variant) {
        NeuralNetwork loaded = load(numPlayers, trainingTarget, variant);
        return loaded != null ? loaded
                : new NeuralNetwork(StateEncoder.inputSize(numPlayers), HIDDEN1, HIDDEN2);
    }

    private NeuralNetwork load(int numPlayers, QLearningTarget trainingTarget, String variant) {
        File file = modelFile(numPlayers, trainingTarget, variant);
        if (file.exists()) {
            try {
                return objectMapper.readValue(file, NeuralNetwork.class);
            } catch (IOException e) {
                // Fall through to the classpath fallback.
            }
        }
        // Classpath fallback only for the base (no-variant) model
        if (variant == null) {
            try {
                ClassPathResource resource = new ClassPathResource(MODEL_DIR + fileName(numPlayers, trainingTarget, null));
                if (!resource.exists()) return null;
                return objectMapper.readValue(resource.getInputStream(), NeuralNetwork.class);
            } catch (IOException e) {
                return null;
            }
        }
        return null;
    }

    synchronized void save(NeuralNetwork network, int numPlayers, QLearningTarget trainingTarget) {
        save(network, numPlayers, trainingTarget, null);
    }

    synchronized void save(NeuralNetwork network, int numPlayers, QLearningTarget trainingTarget, String variant) {
        writeToFile(network, modelFile(numPlayers, trainingTarget, variant));
        cache.put(cacheKey(numPlayers, trainingTarget, variant), network);
    }

    NeuralNetwork getOrCreateFrozen(int numPlayers, QLearningTarget trainingTarget) {
        return getOrCreateFrozen(numPlayers, trainingTarget, null);
    }

    NeuralNetwork getOrCreateFrozen(int numPlayers, QLearningTarget trainingTarget, String variant) {
        return cache.computeIfAbsent(frozenCacheKey(numPlayers, trainingTarget, variant),
                ignored -> loadOrCreateFrozen(numPlayers, trainingTarget, variant));
    }

    synchronized void syncFrozenModel(int numPlayers, QLearningTarget trainingTarget) {
        syncFrozenModel(numPlayers, trainingTarget, null);
    }

    synchronized void syncFrozenModel(int numPlayers, QLearningTarget trainingTarget, String variant) {
        NeuralNetwork copy = deepCopy(getOrCreate(numPlayers, trainingTarget, variant));
        writeToFile(copy, frozenModelFile(numPlayers, trainingTarget, variant));
        cache.put(frozenCacheKey(numPlayers, trainingTarget, variant), copy);
    }

    long getTrainingRuns(int numPlayers, QLearningTarget trainingTarget) {
        return getTrainingRuns(numPlayers, trainingTarget, null);
    }

    long getTrainingRuns(int numPlayers, QLearningTarget trainingTarget, String variant) {
        return getOrCreate(numPlayers, trainingTarget, variant).getTrainingRuns();
    }

    // Copies the base model file to a city-variant file. Skips if the variant already exists.
    synchronized void copyBaseToVariant(int numPlayers, QLearningTarget trainingTarget, String variant) {
        File base = modelFile(numPlayers, trainingTarget, null);
        File target = modelFile(numPlayers, trainingTarget, variant);
        if (!base.exists()) {
            throw new IllegalStateException("Base model file not found: " + base);
        }
        if (target.exists()) return;
        try {
            target.getParentFile().mkdirs();
            Files.copy(base.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to copy base model to variant " + variant, e);
        }
    }

    private NeuralNetwork loadOrCreateFrozen(int numPlayers, QLearningTarget trainingTarget, String variant) {
        File file = frozenModelFile(numPlayers, trainingTarget, variant);
        if (file.exists()) {
            try {
                return objectMapper.readValue(file, NeuralNetwork.class);
            } catch (IOException ignored) {
            }
        }
        // No frozen snapshot yet — copy the live model as the initial baseline
        NeuralNetwork copy = deepCopy(getOrCreate(numPlayers, trainingTarget, variant));
        writeToFile(copy, file);
        return copy;
    }

    private NeuralNetwork deepCopy(NeuralNetwork network) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsBytes(network), NeuralNetwork.class);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to deep-copy neural network", e);
        }
    }

    private void writeToFile(NeuralNetwork network, File target) {
        try {
            target.getParentFile().mkdirs();
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, network);
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to save Q-learning model for " + target.getName(), e);
        }
    }

    private File modelFile(int numPlayers, QLearningTarget trainingTarget, String variant) {
        String projectRoot = System.getProperty("user.dir");
        return new File(projectRoot, "src/main/resources/" + MODEL_DIR + fileName(numPlayers, trainingTarget, variant));
    }

    private File frozenModelFile(int numPlayers, QLearningTarget trainingTarget, String variant) {
        String projectRoot = System.getProperty("user.dir");
        String base = fileName(numPlayers, trainingTarget, variant);
        return new File(projectRoot, "src/main/resources/" + MODEL_DIR + base.replace(".json", "-frozen.json"));
    }

    private static String fileName(int numPlayers, QLearningTarget trainingTarget, String variant) {
        String base = "q-learning-model-" + numPlayers + "p-" + trainingTarget.modelKey() + "-" + modelVersion(trainingTarget);
        if (variant != null && !variant.isBlank()) {
            return base + "-" + variant.toUpperCase(Locale.ROOT) + ".json";
        }
        return base + ".json";
    }

    private static String cacheKey(int numPlayers, QLearningTarget trainingTarget, String variant) {
        String key = numPlayers + ":" + trainingTarget.name();
        return variant != null && !variant.isBlank() ? key + ":" + variant.toUpperCase(Locale.ROOT) : key;
    }

    private static String frozenCacheKey(int numPlayers, QLearningTarget trainingTarget, String variant) {
        return cacheKey(numPlayers, trainingTarget, variant) + ":frozen";
    }

    private static String modelVersion(QLearningTarget trainingTarget) {
        return switch (trainingTarget) {
            case TERMINAL_OUTCOME -> TERMINAL_OUTCOME_MODEL_VERSION;
            case INFLUENCE -> INFLUENCE_MODEL_VERSION;
        };
    }
}

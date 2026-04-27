package de.neebs.franchise.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
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
        return cache.computeIfAbsent(cacheKey(numPlayers, trainingTarget),
                ignored -> loadOrCreate(numPlayers, trainingTarget));
    }

    private NeuralNetwork loadOrCreate(int numPlayers, QLearningTarget trainingTarget) {
        NeuralNetwork loaded = load(numPlayers, trainingTarget);
        return loaded != null ? loaded
                : new NeuralNetwork(StateEncoder.inputSize(numPlayers), HIDDEN1, HIDDEN2);
    }

    private NeuralNetwork load(int numPlayers, QLearningTarget trainingTarget) {
        File file = modelFile(numPlayers, trainingTarget);
        if (file.exists()) {
            try {
                return objectMapper.readValue(file, NeuralNetwork.class);
            } catch (IOException e) {
                // Fall through to the classpath fallback.
            }
        }
        try {
            ClassPathResource resource = new ClassPathResource(MODEL_DIR + fileName(numPlayers, trainingTarget));
            if (!resource.exists()) return null;
            return objectMapper.readValue(resource.getInputStream(), NeuralNetwork.class);
        } catch (IOException e) {
            return null;
        }
    }

    synchronized void save(NeuralNetwork network, int numPlayers, QLearningTarget trainingTarget) {
        writeToFile(network, modelFile(numPlayers, trainingTarget));
        cache.put(cacheKey(numPlayers, trainingTarget), network);
    }

    NeuralNetwork getOrCreateFrozen(int numPlayers, QLearningTarget trainingTarget) {
        return cache.computeIfAbsent(frozenCacheKey(numPlayers, trainingTarget),
                ignored -> loadOrCreateFrozen(numPlayers, trainingTarget));
    }

    synchronized void syncFrozenModel(int numPlayers, QLearningTarget trainingTarget) {
        NeuralNetwork copy = deepCopy(getOrCreate(numPlayers, trainingTarget));
        writeToFile(copy, frozenModelFile(numPlayers, trainingTarget));
        cache.put(frozenCacheKey(numPlayers, trainingTarget), copy);
    }

    long getTrainingRuns(int numPlayers, QLearningTarget trainingTarget) {
        return getOrCreate(numPlayers, trainingTarget).getTrainingRuns();
    }

    private NeuralNetwork loadOrCreateFrozen(int numPlayers, QLearningTarget trainingTarget) {
        File file = frozenModelFile(numPlayers, trainingTarget);
        if (file.exists()) {
            try {
                return objectMapper.readValue(file, NeuralNetwork.class);
            } catch (IOException ignored) {
            }
        }
        // No frozen snapshot yet — copy the live model as the initial baseline
        NeuralNetwork copy = deepCopy(getOrCreate(numPlayers, trainingTarget));
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

    private File modelFile(int numPlayers, QLearningTarget trainingTarget) {
        String projectRoot = System.getProperty("user.dir");
        return new File(projectRoot, "src/main/resources/" + MODEL_DIR + fileName(numPlayers, trainingTarget));
    }

    private File frozenModelFile(int numPlayers, QLearningTarget trainingTarget) {
        String projectRoot = System.getProperty("user.dir");
        String base = fileName(numPlayers, trainingTarget);
        return new File(projectRoot, "src/main/resources/" + MODEL_DIR + base.replace(".json", "-frozen.json"));
    }

    private static String fileName(int numPlayers, QLearningTarget trainingTarget) {
        return "q-learning-model-" + numPlayers + "p-" + trainingTarget.modelKey() + "-" + modelVersion(trainingTarget) + ".json";
    }

    private static String cacheKey(int numPlayers, QLearningTarget trainingTarget) {
        return numPlayers + ":" + trainingTarget.name();
    }

    private static String frozenCacheKey(int numPlayers, QLearningTarget trainingTarget) {
        return numPlayers + ":" + trainingTarget.name() + ":frozen";
    }

    private static String modelVersion(QLearningTarget trainingTarget) {
        return switch (trainingTarget) {
            case TERMINAL_OUTCOME -> TERMINAL_OUTCOME_MODEL_VERSION;
            case INFLUENCE -> INFLUENCE_MODEL_VERSION;
        };
    }
}

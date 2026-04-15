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
 * Loads and saves value networks used by the Monte Carlo tree search strategy.
 */
@Service
class MonteCarloTreeSearchModelService {

    private static final String MODEL_DIR = "monte-carlo-tree-search/";
    private static final int HIDDEN1 = 256;
    private static final int HIDDEN2 = 128;

    private final ObjectMapper objectMapper;
    private final Map<Integer, NeuralNetwork> cache = new ConcurrentHashMap<>();

    MonteCarloTreeSearchModelService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    NeuralNetwork getOrCreate(int numPlayers) {
        return cache.computeIfAbsent(numPlayers, this::loadOrCreate);
    }

    private NeuralNetwork loadOrCreate(int numPlayers) {
        NeuralNetwork loaded = load(numPlayers);
        return loaded != null ? loaded
                : new NeuralNetwork(StateEncoder.inputSize(numPlayers), HIDDEN1, HIDDEN2);
    }

    private NeuralNetwork load(int numPlayers) {
        File file = modelFile(numPlayers);
        if (file.exists()) {
            try {
                return objectMapper.readValue(file, NeuralNetwork.class);
            } catch (IOException e) {
                // Fall through to the classpath fallback.
            }
        }
        try {
            ClassPathResource resource = new ClassPathResource(MODEL_DIR + fileName(numPlayers));
            if (!resource.exists()) return null;
            return objectMapper.readValue(resource.getInputStream(), NeuralNetwork.class);
        } catch (IOException e) {
            return null;
        }
    }

    synchronized void save(NeuralNetwork network, int numPlayers) {
        try {
            File target = modelFile(numPlayers);
            target.getParentFile().mkdirs();
            File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp, network);
            Files.move(tmp.toPath(), target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            cache.put(numPlayers, network);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to save Monte Carlo tree search model for " + numPlayers + " players", e);
        }
    }

    private File modelFile(int numPlayers) {
        String projectRoot = System.getProperty("user.dir");
        return new File(projectRoot, "src/main/resources/" + MODEL_DIR + fileName(numPlayers));
    }

    private static String fileName(int numPlayers) {
        return "monte-carlo-tree-search-model-" + numPlayers + "p.json";
    }
}

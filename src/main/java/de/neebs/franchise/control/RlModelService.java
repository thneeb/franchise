package de.neebs.franchise.control;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads and saves {@link NeuralNetwork} models keyed by player count.
 *
 * <p>Load order:
 * <ol>
 *   <li>Filesystem — {@code src/main/resources/rl/rl-model-{n}p.json} (written by training runs)</li>
 *   <li>Classpath fallback — pre-trained model bundled with the application</li>
 *   <li>Fresh He-initialised network if neither exists</li>
 * </ol>
 *
 * <p>Models are saved to {@code src/main/resources/rl/} so they survive server restarts
 * and can be committed alongside source, mirroring how calibration configs are stored.
 */
@Service
class RlModelService {

    private static final String RL_DIR = "rl/";
    private static final int HIDDEN1 = 256;
    private static final int HIDDEN2 = 128;

    private final ObjectMapper objectMapper;
    private final Map<Integer, NeuralNetwork> cache = new ConcurrentHashMap<>();

    RlModelService(ObjectMapper objectMapper) {
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
                // Corrupted file — fall through to classpath
            }
        }
        try {
            ClassPathResource resource = new ClassPathResource(RL_DIR + fileName(numPlayers));
            if (!resource.exists()) return null;
            return objectMapper.readValue(resource.getInputStream(), NeuralNetwork.class);
        } catch (IOException e) {
            return null;
        }
    }

    synchronized void save(NeuralNetwork network, int numPlayers) {
        try {
            File file = modelFile(numPlayers);
            file.getParentFile().mkdirs();
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, network);
            cache.put(numPlayers, network);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to save RL model for " + numPlayers + " players", e);
        }
    }

    private File modelFile(int numPlayers) {
        String projectRoot = System.getProperty("user.dir");
        return new File(projectRoot, "src/main/resources/" + RL_DIR + fileName(numPlayers));
    }

    private static String fileName(int numPlayers) {
        return "rl-model-" + numPlayers + "p.json";
    }
}

package de.neebs.franchise.control;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;

/**
 * Thin HTTP client for the OpenRouter chat completions API.
 * Reads the API key from the OPENROUTER_API_KEY environment variable,
 * falling back to a .env file in the project root.
 */
@Service
class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final String API_URL = "https://openrouter.ai/api/v1/chat/completions";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final String apiKey;

    LlmClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.apiKey = resolveApiKey();
    }

    /**
     * Sends a chat completion request and returns the assistant message content.
     *
     * @param model      OpenRouter model ID, e.g. "anthropic/claude-haiku-4-5"
     * @param systemPrompt system message content
     * @param userPrompt   user message content
     * @return assistant reply text, or null on failure
     */
    String complete(String model, String systemPrompt, String userPrompt) {
        if (apiKey == null) {
            log.warn("OPENROUTER_API_KEY not found — LLM strategy will use fallback");
            return null;
        }
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            ArrayNode messages = body.putArray("messages");
            messages.addObject().put("role", "system").put("content", systemPrompt);
            messages.addObject().put("role", "user").put("content", userPrompt);

            String requestBody = objectMapper.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("HTTP-Referer", "https://github.com/franchise-game")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("OpenRouter returned HTTP {}: {}", response.statusCode(), response.body());
                return null;
            }
            JsonNode json = objectMapper.readTree(response.body());
            return json.path("choices").path(0).path("message").path("content").asText(null);
        } catch (Exception e) {
            log.warn("LLM call failed: {}", e.getMessage());
            return null;
        }
    }

    private static String resolveApiKey() {
        String fromEnv = System.getenv("OPENROUTER_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        File envFile = new File(System.getProperty("user.dir"), ".env");
        if (envFile.exists()) {
            try {
                for (String line : Files.readAllLines(envFile.toPath())) {
                    if (line.startsWith("OPENROUTER_API_KEY=")) {
                        String value = line.substring("OPENROUTER_API_KEY=".length()).trim();
                        if (!value.isBlank()) return value;
                    }
                }
            } catch (IOException ignored) {}
        }
        return null;
    }
}

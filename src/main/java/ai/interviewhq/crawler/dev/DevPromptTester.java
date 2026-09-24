package ai.interviewhq.crawler.dev;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Service
@Profile({"dev", "local"})
public class DevPromptTester {
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;
    private final String reasoningEffort;

    public DevPromptTester(ObjectMapper objectMapper,
                           @Value("${OPENAI_API_KEY:}") String apiKey,
                           @Value("${OPENAI_MODEL:gpt-5-nano}") String model,
                           @Value("${OPENAI_REASONING_EFFORT:low}") String reasoningEffort) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.reasoningEffort = reasoningEffort;
    }

    public JsonNode test(String prompt) throws Exception {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Prompt is required");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalStateException("OPENAI_API_KEY is not configured");

        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", model);
        request.put("input", prompt + "\n\nReturn ONLY valid JSON. Do not use Markdown fences or explanatory text.");
        request.put("store", false);
        ObjectNode reasoning = objectMapper.createObjectNode();
        reasoning.put("effort", reasoningEffort);
        request.set("reasoning", reasoning);
        ObjectNode text = objectMapper.createObjectNode();
        text.put("verbosity", "low");
        request.set("text", text);

        HttpRequest httpRequest = HttpRequest.newBuilder(RESPONSES_URI)
                .timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI API request failed: HTTP " + response.statusCode() + " - " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        String outputText = root.path("output_text").asText(null);
        if (outputText != null && !outputText.isBlank()) return objectMapper.readTree(cleanJson(outputText));

        JsonNode output = root.path("output");
        if (output.isArray()) {
            for (JsonNode item : output) {
                if (!"message".equals(item.path("type").asText())) continue;
                JsonNode content = item.path("content");
                if (!content.isArray()) continue;
                for (JsonNode contentItem : content) {
                    if (!"output_text".equals(contentItem.path("type").asText())) continue;
                    String textValue = contentItem.path("text").asText(null);
                    if (textValue != null && !textValue.isBlank()) return objectMapper.readTree(cleanJson(textValue));
                }
            }
        }
        throw new IllegalStateException("OpenAI response did not contain JSON output");
    }

    private static String cleanJson(String value) {
        String text = value.trim();
        if (text.startsWith("```json")) text = text.substring(7).trim();
        else if (text.startsWith("```")) text = text.substring(3).trim();
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3).trim();
        return text;
    }
}

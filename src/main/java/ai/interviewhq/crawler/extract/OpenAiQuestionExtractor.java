package ai.interviewhq.crawler.extract;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
public class OpenAiQuestionExtractor {
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CrawlerSettings settings;
    private final String apiKey;

    public OpenAiQuestionExtractor(ObjectMapper objectMapper, CrawlerSettings settings,
                                   @Value("${OPENAI_API_KEY:}") String apiKey) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.apiKey = apiKey;
    }

    public InterviewQuestion extract(ParsedEntry entry, Integer postId) {
        String text = entry.bodyText();
        if (text == null || text.isBlank()) return null;
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured");
        }

        try {
            String requestBody = objectMapper.createObjectNode()
                    .put("model", settings.extractModel())
                    .put("input", buildPrompt(entry, text))
                    .put("max_output_tokens", settings.extractMaxTokens())
                    .toString();

            HttpRequest request = HttpRequest.newBuilder(RESPONSES_URI)
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OpenAI API request failed: HTTP "
                        + response.statusCode() + " - " + response.body());
            }

            String output = extractOutputText(response.body());
            if (output == null || output.isBlank()) return null;

            ExtractedQuestion extracted = objectMapper.readValue(cleanJson(output), ExtractedQuestion.class);
            if (extracted.questionText() == null || extracted.questionText().isBlank()) return null;

            InterviewQuestion question = new InterviewQuestion();
            question.setPostId(postId);
            question.setCompany(firstNonBlank(extracted.company(), entry.rawCompany()));
            question.setRole(firstNonBlank(extracted.role(), entry.rawRole()));
            question.setLevel(extracted.level());
            question.setRoundType(extracted.roundType());
            question.setQuestionType(extracted.questionType());
            question.setQuestionText(extracted.questionText().trim());
            question.setDifficulty(extracted.difficulty());
            question.setTopics(extracted.topics() == null ? Collections.emptyList() : extracted.topics());
            question.setConfidence(extracted.confidence());
            question.setModelName(settings.extractModel());
            question.setExtractedAt(Instant.now());
            question.setDedupeHash(Hashing.questionDedupeHash(question.getCompany(), question.getQuestionText()));
            return question;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI extraction interrupted", e);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to extract interview question with OpenAI", e);
        }
    }

    private String buildPrompt(ParsedEntry entry, String text) {
        return "Extract ONE software engineering interview question from the content below.\n"
                + "If the content does not contain an interview question, return {\"questionText\":null}.\n"
                + "Do not invent information.\n\n"
                + "Return JSON only: {\n"
                + "  \"company\": \"string or null\",\n"
                + "  \"role\": \"string or null\",\n"
                + "  \"level\": \"string or null\",\n"
                + "  \"roundType\": \"string or null\",\n"
                + "  \"questionType\": \"string or null\",\n"
                + "  \"questionText\": \"string or null\",\n"
                + "  \"difficulty\": \"string or null\",\n"
                + "  \"topics\": [\"string\"],\n"
                + "  \"confidence\": 0.0\n}\n\n"
                + "Title: " + nullToEmpty(entry.title()) + "\n"
                + "Company hint: " + nullToEmpty(entry.rawCompany()) + "\n"
                + "Role hint: " + nullToEmpty(entry.rawRole()) + "\n\n"
                + "Content:\n" + text;
    }

    private String extractOutputText(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode outputText = root.get("output_text");
        if (outputText != null && outputText.isTextual()) return outputText.asText();

        JsonNode output = root.get("output");
        if (output == null || !output.isArray()) return null;
        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) continue;
            for (JsonNode contentItem : content) {
                JsonNode value = contentItem.get("text");
                if (value != null && value.isTextual()) {
                    if (text.length() > 0) text.append('\n');
                    text.append(value.asText());
                }
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private static String cleanJson(String response) {
        String value = response.trim();
        if (value.startsWith("```json")) value = value.substring(7).trim();
        else if (value.startsWith("```")) value = value.substring(3).trim();
        if (value.endsWith("```")) value = value.substring(0, value.length() - 3).trim();
        return value;
    }

    private static String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private static String nullToEmpty(String value) { return value == null ? "" : value; }

    private record ExtractedQuestion(String company, String role, String level, String roundType,
                                     String questionType, String questionText, String difficulty,
                                     List<String> topics, Float confidence) {}
}
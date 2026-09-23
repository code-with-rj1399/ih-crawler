package ai.interviewhq.crawler.extract;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Model is used only to structure questions from page text the crawler already
 * fetched. Discovery, browsing, and web_search tools are intentionally disabled.
 */
@Service
public class OpenAiQuestionExtractor {
    private static final Logger log = LoggerFactory.getLogger(OpenAiQuestionExtractor.class);
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final int MAX_CONTENT_CHARS = 12_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CrawlerSettings settings;
    private final String apiKey;
    private final ExtractionPromptService promptService;

    public OpenAiQuestionExtractor(ObjectMapper objectMapper, CrawlerSettings settings,
                                   ExtractionPromptService promptService,
                                   @Value("${OPENAI_API_KEY:}") String apiKey) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.promptService = promptService;
        this.apiKey = apiKey;
    }

    public List<InterviewQuestion> extractQuestionsFromContent(CrawlSource source, String postUrl,
                                                                 String title, String author,
                                                                 Instant publishedAt, String bodyText) {
        if (source == null || postUrl == null || postUrl.isBlank() || bodyText == null || bodyText.isBlank()) {
            return Collections.emptyList();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OPENAI_API_KEY missing — skipping extraction for {}", postUrl);
            return Collections.emptyList();
        }

        try {
            String prompt = promptService.getPrompt()
                    .replace("{{source_platform}}", source.getName())
                    .replace("{{post_url}}", postUrl)
                    .replace("{{title}}", title == null ? "" : title)
                    .replace("{{author}}", author == null ? "" : author)
                    .replace("{{published_at}}", publishedAt == null ? "" : publishedAt.toString())
                    .replace("{{tags}}", "")
                    .replace("{{page_content}}", truncate(bodyText));

            JsonNode root = callOpenAi(prompt, questionExtractionSchema(), source);
            String output = extractOutputText(root);
            if (output == null || output.isBlank()) {
                return Collections.emptyList();
            }

            JsonNode extracted = objectMapper.readTree(cleanJson(output));
            JsonNode experience = extracted.path("experience");
            String experienceTitle = nullableText(experience, "title");
            String experienceAuthor = nullableText(experience, "author");
            Instant experiencePostedAt = nullableInstant(experience, "postedAt");

            JsonNode questionsNode = extracted.path("questions");
            if (!questionsNode.isArray()) {
                return Collections.emptyList();
            }

            List<InterviewQuestion> questions = new ArrayList<>();
            for (JsonNode node : questionsNode) {
                ExtractedQuestion item = objectMapper.treeToValue(node, ExtractedQuestion.class);
                if (item == null || item.questionText() == null || item.questionText().isBlank()) {
                    continue;
                }
                if (item.company() == null || item.company().isBlank()) {
                    log.info("Rejecting extracted question without company: source={}, postUrl={}",
                            source.getSlug(), postUrl);
                    continue;
                }

                if (!isHighQualityQuestion(item)) {
                    log.info("Rejecting low-quality extracted question: source={} postUrl={} question={}", source.getSlug(), postUrl, item.questionText());
                    continue;
                }

                InterviewQuestion question = new InterviewQuestion();
                question.setSourcePlatform(firstNonBlank(item.sourcePlatform(), source.getName()));
                question.setExperienceTitle(experienceTitle);
                question.setExperienceAuthor(experienceAuthor);
                question.setExperiencePostedAt(experiencePostedAt != null ? experiencePostedAt : publishedAt);
                question.setOriginalPostUrl(postUrl);
                question.setProblemUrl(normalizeProblemUrl(item.problemUrl()));
                question.setPostDate(item.postDate() != null
                        ? item.postDate()
                        : publishedAt == null ? null : publishedAt.atZone(ZoneOffset.UTC).toLocalDate());
                question.setCompany(item.company());
                question.setLevel(item.level());
                question.setLocation(item.location());
                question.setCandidateYoE(item.candidateYoE());
                question.setOutcome(item.outcome());
                question.setRoundType(item.roundType());
                question.setQuestionType(normalizeQuestionType(item.questionType()));
                question.setQuestionText(item.questionText().trim());
                question.setQuestionDescription(item.questionDescription());
                question.setTopics(item.topics() == null ? Collections.emptyList() : item.topics());
                question.setConfidence(item.confidence());
                question.setQuestionSpecificity(item.questionSpecificity());
                question.setModelName(settings.extractModel());
                question.setExtractedAt(Instant.now());
                question.setDedupeHash(Hashing.questionDedupeHash(
                        question.getCompany(), question.getQuestionText()));
                questions.add(question);
            }

            return questions;
        } catch (Exception e) {
            throw new IllegalStateException("Unable to extract interview questions from " + postUrl, e);
        }
    }


    /** Deterministic quality gate: LLM output is a candidate, not truth. */
    private boolean isHighQualityQuestion(ExtractedQuestion item) {
        String text = item.questionText() == null ? "" : item.questionText().trim();
        if (text.isBlank() || text.length() < 8 || text.length() > 140) return false;
        float confidence = item.confidence() == null ? 0f : item.confidence();
        if (confidence < 0.70f) return false;
        String normalized = text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ").replaceAll("\\s+", " ").trim();
        Set<String> weakExact = Set.of(
                "explain your project", "explain your project architecture",
                "tell me about your project", "tell me about yourself",
                "introduce yourself", "what is your project",
                "what are you working on", "how was your interview",
                "how did the interview go");
        if (weakExact.contains(normalized)) return false;
        String[] weakStarts = {"are you using ", "do you use ", "have you used ",
                "have you worked with ", "what tools do you use ",
                "what technology do you use ", "what tech stack ",
                "what is your experience with "};
        for (String prefix : weakStarts) if (normalized.startsWith(prefix)) return false;
        return true;
    }
    private JsonNode callOpenAi(String prompt, JsonNode schema, CrawlSource source) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", settings.extractModel());
        request.put("input", prompt);
        request.put("max_output_tokens", settings.extractMaxTokens());
        // Explicitly empty — never enable web_search / browsing tools.
        request.set("tools", objectMapper.createArrayNode());
        request.put("store", false);

        ObjectNode reasoning = objectMapper.createObjectNode();
        reasoning.put("effort", "low");
        request.set("reasoning", reasoning);

        ObjectNode text = objectMapper.createObjectNode();
        text.set("format", schema.path("format"));
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
            throw new IllegalStateException("OpenAI API request failed: HTTP "
                    + response.statusCode() + " - " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        log.info("OpenAI extraction diagnostics: source={}, responseId={}, status={}, outputTypes={}, usage={}",
                source.getSlug(),
                root.path("id").asText("unknown"),
                root.path("status").asText("unknown"),
                outputTypes(root),
                root.path("usage"));

        if ("incomplete".equals(root.path("status").asText())) {
            throw new IllegalStateException("OpenAI response incomplete: reason="
                    + root.path("incomplete_details").path("reason").asText("unknown")
                    + ", usage=" + root.path("usage"));
        }
        return root;
    }

    private JsonNode questionExtractionSchema() {
        var format = objectMapper.createObjectNode()
                .put("type", "json_schema")
                .put("name", "interview_question_extraction")
                .put("strict", true);

        var question = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        var properties = objectMapper.createObjectNode();
        properties.set("sourcePlatform", nullableStringSchema());
        properties.set("problemUrl", nullableStringSchema());
        properties.set("postDate", nullableStringSchema());
        properties.set("company", nullableStringSchema());
        properties.set("level", nullableStringSchema());
        properties.set("location", nullableStringSchema());
        properties.set("candidateYoE", nullableNumberSchema());
        properties.set("outcome", nullableStringSchema());
        properties.set("roundType", nullableStringSchema());
        properties.set("questionType", nullableStringSchema());
        properties.set("questionText", nullableStringSchema());
        properties.set("questionDescription", nullableStringSchema());
                ObjectNode topicsSchema = objectMapper.createObjectNode().put("type", "array");
        topicsSchema.set("items", objectMapper.createObjectNode().put("type", "string"));
        properties.set("topics", topicsSchema);
        properties.set("confidence", nullableNumberSchema());
        properties.set("questionSpecificity", nullableNumberSchema());
        question.set("properties", properties);
        question.set("required", objectMapper.createArrayNode()
                 .add("sourcePlatform").add("problemUrl").add("postDate").add("company").add("level")
                .add("location").add("candidateYoE").add("outcome").add("roundType")
                .add("questionType").add("questionText").add("questionDescription")
                .add("topics").add("confidence").add("questionSpecificity"));

        var schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode schemaProperties = objectMapper.createObjectNode();

        ObjectNode sourceSchema = objectMapper.createObjectNode()
                .put("type", "object").put("additionalProperties", false);
        ObjectNode sourceProperties = objectMapper.createObjectNode();
        sourceProperties.set("name", objectMapper.createObjectNode().put("type", "string"));
        sourceProperties.set("url", objectMapper.createObjectNode().put("type", "string"));
        sourceSchema.set("properties", sourceProperties);
        sourceSchema.set("required", objectMapper.createArrayNode().add("name").add("url"));

        ObjectNode experienceSchema = objectMapper.createObjectNode()
                .put("type", "object").put("additionalProperties", false);
        ObjectNode experienceProperties = objectMapper.createObjectNode();
        experienceProperties.set("title", nullableStringSchema());
        experienceProperties.set("postedAt", nullableStringSchema());
        experienceProperties.set("author", nullableStringSchema());
        experienceSchema.set("properties", experienceProperties);
        experienceSchema.set("required", objectMapper.createArrayNode().add("title").add("postedAt").add("author"));

        ObjectNode questionsSchema = objectMapper.createObjectNode().put("type", "array");
        questionsSchema.set("items", question);

        schemaProperties.set("source", sourceSchema);
        schemaProperties.set("experience", experienceSchema);
        schemaProperties.set("questions", questionsSchema);
        schema.set("properties", schemaProperties);
        schema.set("required", objectMapper.createArrayNode().add("source").add("experience").add("questions"));
        format.set("schema", schema);
        return objectMapper.createObjectNode().set("format", format);
    }

    private JsonNode nullableNumberSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode types = objectMapper.createArrayNode();
        types.add("number").add("null");
        schema.set("type", types);
        return schema;
    }

    private JsonNode nullableStringSchema() {
        ObjectNode schema = objectMapper.createObjectNode();
        ArrayNode types = objectMapper.createArrayNode();
        types.add("string").add("null");
        schema.set("type", types);
        return schema;
    }

    private String extractOutputText(JsonNode root) {
        JsonNode outputText = root.get("output_text");
        if (outputText != null && outputText.isTextual() && !outputText.asText().isBlank()) {
            return outputText.asText();
        }

        JsonNode output = root.get("output");
        if (output == null || !output.isArray()) {
            return null;
        }

        StringBuilder text = new StringBuilder();
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asText())) {
                continue;
            }
            JsonNode content = item.get("content");
            if (content == null || !content.isArray()) {
                continue;
            }
            for (JsonNode contentItem : content) {
                if (!"output_text".equals(contentItem.path("type").asText())) {
                    continue;
                }
                JsonNode value = contentItem.get("text");
                if (value != null && value.isTextual() && !value.asText().isBlank()) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(value.asText());
                }
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    private List<String> outputTypes(JsonNode root) {
        JsonNode output = root.path("output");
        if (!output.isArray()) {
            return Collections.emptyList();
        }
        List<String> types = new ArrayList<>();
        for (JsonNode item : output) {
            types.add(item.path("type").asText("unknown"));
        }
        return types;
    }

    private static String truncate(String bodyText) {
        if (bodyText.length() <= MAX_CONTENT_CHARS) {
            return bodyText;
        }
        return bodyText.substring(0, MAX_CONTENT_CHARS);
    }

    private static String cleanJson(String response) {
        String value = response.trim();
        if (value.startsWith("```json")) {
            value = value.substring(7).trim();
        } else if (value.startsWith("```")) {
            value = value.substring(3).trim();
        }
        if (value.endsWith("```")) {
            value = value.substring(0, value.length() - 3).trim();
        }
        return value;
    }

    private static String normalizeQuestionType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CODING" -> "Coding";
            case "DATABASE" -> "Database";
            case "SYSTEM_DESIGN" -> "System Design";
            case "LLD" -> "LLD";
            case "CLOUD" -> "Cloud";
            case "SECURITY" -> "Security";
            case "DEVOPS" -> "DevOps";
            case "AI_ML", "AIML" -> "AI/ML";
            case "DATA_ENGINEERING" -> "Data Engineering";
            case "DISTRIBUTED_SYSTEMS" -> "Distributed Systems";
            case "NETWORKING" -> "Networking";
            case "OPERATING_SYSTEMS" -> "Operating Systems";
            case "PROGRAMMING_LANGUAGE" -> "Programming Language";
            case "WEB_FRONTEND" -> "Web Frontend";
            case "MOBILE" -> "Mobile";
            case "TESTING" -> "Testing";
            case "TECHNICAL_CONCEPT" -> "Technical Concept";
            default -> value.trim();
        };
    }

    private static String normalizeProblemUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String url = value.trim();
        if (!url.startsWith("https://leetcode.com/problems/")) {
            return url;
        }
        int query = url.indexOf("?");
        int fragment = url.indexOf("#");
        int end = url.length();
        if (query >= 0) {
            end = Math.min(end, query);
        }
        if (fragment >= 0) {
            end = Math.min(end, fragment);
        }
        return url.substring(0, end);
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()
                ? null : value.asText().trim();
    }

    private static Instant nullableInstant(JsonNode node, String field) {
        String value = nullableText(node, field);
        if (value == null) return null;
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private record ExtractedQuestion(String sourcePlatform, String originalPostUrl, String problemUrl, LocalDate postDate,
                                     String company, String level, String location, Float candidateYoE,
                                     String outcome, String roundType, String questionType, String questionText,
                                     String questionDescription,
                                     List<String> topics, Float confidence, Float questionSpecificity) {}
}

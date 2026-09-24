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
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/** Two-stage LLM extraction: one page -> experience/questions -> one page-scoped question metadata call. */
@Service
public class TwoStepOpenAiExtractor {
    private static final Logger log = LoggerFactory.getLogger(TwoStepOpenAiExtractor.class);
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final int MAX_CONTENT_CHARS = 12_000;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CrawlerSettings settings;
    private final String apiKey;
    private final String experiencePrompt;
    private final String questionPrompt;

    public TwoStepOpenAiExtractor(ObjectMapper objectMapper, CrawlerSettings settings,
                                  @Value("${OPENAI_API_KEY:}") String apiKey) throws Exception {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.apiKey = apiKey;
        this.experiencePrompt = loadPrompt("prompts/experience_extraction.txt");
        this.questionPrompt = loadPrompt("prompts/question_metadata_extraction.txt");
    }

    public ExtractionResult extract(CrawlSource source, String postUrl, String title, String author,
                                    Instant publishedAt, String bodyText) {
        if (source == null || postUrl == null || postUrl.isBlank() || bodyText == null || bodyText.isBlank()) {
            return ExtractionResult.empty();
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OPENAI_API_KEY missing - skipping extraction for {}", postUrl);
            return ExtractionResult.empty();
        }
        try {
            ExperienceExtraction experience = extractExperience(source, postUrl, title, author, publishedAt, bodyText);
            if (experience.questions().isEmpty()) {
                return new ExtractionResult(experience, List.of());
            }

            List<QuestionMetadata> metadata = extractQuestionMetadata(experience.questions());
            List<InterviewQuestion> questions = new ArrayList<>();
            for (int i = 0; i < experience.questions().size(); i++) {
                if (i >= metadata.size()) {
                    log.warn("Step 2 returned fewer results for {}: candidates={}, metadata={}",
                            postUrl, experience.questions().size(), metadata.size());
                    continue;
                }
                QuestionMetadata item = metadata.get(i);
                if (!item.isValidInterviewQuestion()) continue;
                questions.add(toQuestion(source, postUrl, publishedAt, experience,
                        experience.questions().get(i), item));
            }
            log.info("Question extraction for {}: candidates={}, valid={}, discarded={}", postUrl,
                    experience.questions().size(), questions.size(), experience.questions().size() - questions.size());
            return new ExtractionResult(experience, questions);
        } catch (Exception e) {
            throw new IllegalStateException("Two-step interview extraction failed for " + postUrl, e);
        }
    }

    private ExperienceExtraction extractExperience(CrawlSource source, String postUrl, String title,
                                                   String author, Instant publishedAt, String bodyText) throws Exception {
        String prompt = experiencePrompt
                .replace("{{source_platform}}", safe(source.getName()))
                .replace("{{post_url}}", safe(postUrl))
                .replace("{{title}}", safe(title))
                .replace("{{author}}", safe(author))
                .replace("{{published_at}}", publishedAt == null ? "" : publishedAt.toString())
                .replace("{{page_content}}", truncate(bodyText));
        JsonNode result = parseOutput(callOpenAi(prompt, experienceSchema(), "experience_extraction"));
        if (result == null || !result.isObject()) return ExperienceExtraction.empty();

        JsonNode experience = result.path("experience");
        String extractedTitle = nullableText(experience, "title");
        String summary = nullableText(experience, "summary");
        Instant postedAt = parseInstant(nullableText(experience, "postedAt"));
        if (postedAt == null) postedAt = publishedAt;
        String extractedAuthor = firstNonBlank(nullableText(experience, "author"), author);
        String company = nullableText(experience, "company");
        String role = nullableText(experience, "role");
        String level = nullableText(experience, "level");
        String location = nullableText(experience, "location");
        Float candidateYoE = nullableFloat(experience, "candidateYoE");
        List<QuestionCandidate> candidates = new ArrayList<>();
        JsonNode questions = result.path("questions");
        if (questions.isArray()) {
            for (JsonNode question : questions) {
                String text = question.isTextual() ? question.asText() : nullableText(question, "questionText");
                if (text != null && !text.isBlank()) candidates.add(new QuestionCandidate(text.trim()));
            }
        }
        return new ExperienceExtraction(firstNonBlank(extractedTitle, title), summary, postedAt, extractedAuthor,
                company, role, level, location, candidateYoE, candidates);
    }

    private List<QuestionMetadata> extractQuestionMetadata(List<QuestionCandidate> candidates) throws Exception {
        ArrayNode questions = objectMapper.createArrayNode();
        for (QuestionCandidate candidate : candidates) questions.add(candidate.questionText().trim());
        String prompt = questionPrompt.replace("{{questions_json}}", questions.toString());
        if (prompt.equals(questionPrompt)) prompt = questionPrompt.replace("{{question_text}}", questions.toString());

        JsonNode result = parseOutput(callOpenAi(prompt, questionMetadataSchema(), "question_metadata_extraction"));
        if (result == null || !result.isObject()) return List.of();
        JsonNode items = result.path("questions");
        if (!items.isArray()) return List.of();
        List<QuestionMetadata> metadata = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) continue;
            metadata.add(new QuestionMetadata(nullableText(item, "questionText"),
                    item.path("isValidInterviewQuestion").asBoolean(false), nullableText(item, "questionType"),
                    nullableText(item, "difficulty"), stringList(item.path("topics")),
                    nullableText(item, "questionDescription"), nullableFloat(item, "confidence")));
        }
        return metadata;
    }

    private InterviewQuestion toQuestion(CrawlSource source, String postUrl, Instant publishedAt,
                                         ExperienceExtraction experience, QuestionCandidate candidate,
                                         QuestionMetadata metadata) {
        InterviewQuestion question = new InterviewQuestion();
        question.setSourcePlatform(source.getName()); question.setExperienceTitle(experience.title());
        question.setExperienceSummary(experience.summary());
        question.setExperienceAuthor(experience.author()); question.setExperiencePostedAt(experience.postedAt());
        question.setOriginalPostUrl(postUrl);
        question.setPostDate(experience.postedAt() == null
                ? publishedAt == null ? null : publishedAt.atZone(ZoneOffset.UTC).toLocalDate()
                : experience.postedAt().atZone(ZoneOffset.UTC).toLocalDate());
        question.setCompany(experience.company()); question.setLevel(experience.level());
        question.setLocation(experience.location()); question.setCandidateYoE(experience.candidateYoE());
        question.setQuestionText(candidate.questionText().trim());
        question.setQuestionType(metadata.questionType()); question.setDifficulty(metadata.difficulty());
        question.setTopics(metadata.topics()); question.setQuestionDescription(metadata.questionDescription());
        question.setConfidence(metadata.confidence()); question.setModelName(settings.extractModel());
        question.setExtractedAt(Instant.now());
        question.setDedupeHash(Hashing.questionDedupeHash(question.getCompany(), question.getQuestionText()));
        return question;
    }

    private JsonNode callOpenAi(String prompt, JsonNode schema, String schemaName) throws Exception {
        ObjectNode request = objectMapper.createObjectNode();
        request.put("model", settings.extractModel()); request.put("input", prompt);
        request.put("max_output_tokens", settings.extractMaxTokens());
        request.set("tools", objectMapper.createArrayNode()); request.put("store", false);
        ObjectNode reasoning = objectMapper.createObjectNode(); reasoning.put("effort", "low"); request.set("reasoning", reasoning);
        ObjectNode text = objectMapper.createObjectNode(); text.set("format", schema.path("format"));
        text.put("verbosity", "low"); request.set("text", text);
        HttpRequest httpRequest = HttpRequest.newBuilder(RESPONSES_URI).timeout(Duration.ofSeconds(180))
                .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(request.toString())).build();
        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI API request failed: HTTP " + response.statusCode() + " - " + response.body());
        }
        JsonNode root = objectMapper.readTree(response.body());
        log.info("OpenAI {} diagnostics: responseId={}, status={}, usage={}", schemaName,
                root.path("id").asText("unknown"), root.path("status").asText("unknown"), root.path("usage"));
        if ("incomplete".equals(root.path("status").asText())) {
            throw new IllegalStateException("OpenAI response incomplete: reason="
                    + root.path("incomplete_details").path("reason").asText("unknown"));
        }
        return root;
    }

    private JsonNode parseOutput(JsonNode root) throws Exception {
        if (root == null) return null;
        String outputText = root.path("output_text").asText(null);
        if (outputText != null && !outputText.isBlank()) return objectMapper.readTree(cleanJson(outputText));
        JsonNode output = root.path("output");
        if (!output.isArray()) return null;
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asText())) continue;
            JsonNode content = item.path("content");
            if (!content.isArray()) continue;
            for (JsonNode contentItem : content) {
                if (!"output_text".equals(contentItem.path("type").asText())) continue;
                String text = contentItem.path("text").asText(null);
                if (text != null && !text.isBlank()) return objectMapper.readTree(cleanJson(text));
            }
        }
        return null;
    }

    private JsonNode experienceSchema() {
        ObjectNode format = jsonSchemaFormat("experience_extraction"); ObjectNode root = objectSchema();
        ObjectNode props = objectMapper.createObjectNode();
        ObjectNode experience = objectSchema(); ObjectNode ep = objectMapper.createObjectNode();
        ep.set("title", nullableStringSchema()); ep.set("summary", nullableStringSchema()); ep.set("postedAt", nullableStringSchema());
        ep.set("author", nullableStringSchema()); ep.set("company", nullableStringSchema()); ep.set("role", nullableStringSchema());
        ep.set("level", nullableStringSchema()); ep.set("location", nullableStringSchema()); ep.set("candidateYoE", nullableNumberSchema());
        experience.set("properties", ep);
        experience.set("required", required("title", "summary", "postedAt", "author", "company", "role", "level", "location", "candidateYoE"));
        props.set("experience", experience); props.set("questions", stringArraySchema());
        root.set("properties", props); root.set("required", required("experience", "questions"));
        format.set("schema", root); return objectMapper.createObjectNode().set("format", format);
    }

    private JsonNode questionMetadataSchema() {
        ObjectNode format = jsonSchemaFormat("question_metadata_extraction");
        ObjectNode item = objectSchema(); ObjectNode props = objectMapper.createObjectNode();
        props.set("questionText", nullableStringSchema());
        props.set("isValidInterviewQuestion", objectMapper.createObjectNode().put("type", "boolean"));
        props.set("questionType", nullableStringSchema()); props.set("difficulty", nullableStringSchema());
        props.set("topics", stringArraySchema()); props.set("questionDescription", nullableStringSchema());
        props.set("confidence", nullableNumberSchema()); item.set("properties", props);
        item.set("required", required("questionText", "isValidInterviewQuestion", "questionType", "difficulty", "topics", "questionDescription", "confidence"));
        ObjectNode root = objectSchema();
        root.set("properties", objectMapper.createObjectNode().set("questions", objectMapper.createObjectNode()
                .put("type", "array").set("items", item)));
        root.set("required", required("questions"));
        format.set("schema", root);
        return objectMapper.createObjectNode().set("format", format);
    }

    private ObjectNode jsonSchemaFormat(String name) { return objectMapper.createObjectNode().put("type", "json_schema").put("name", name).put("strict", true); }
    private ObjectNode objectSchema() { return objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false); }
    private ArrayNode required(String... names) { ArrayNode array = objectMapper.createArrayNode(); for (String name : names) array.add(name); return array; }
    private JsonNode stringArraySchema() { ObjectNode schema = objectMapper.createObjectNode().put("type", "array"); schema.set("items", objectMapper.createObjectNode().put("type", "string")); return schema; }
    private JsonNode nullableStringSchema() { ObjectNode schema = objectMapper.createObjectNode(); schema.set("type", objectMapper.createArrayNode().add("string").add("null")); return schema; }
    private JsonNode nullableNumberSchema() { ObjectNode schema = objectMapper.createObjectNode(); schema.set("type", objectMapper.createArrayNode().add("number").add("null")); return schema; }
    private static String loadPrompt(String path) throws Exception { try (var stream = new ClassPathResource(path).getInputStream()) { return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); } }
    private static String truncate(String text) { return text.length() <= MAX_CONTENT_CHARS ? text : text.substring(0, MAX_CONTENT_CHARS); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String cleanJson(String value) { String text = value.trim(); if (text.startsWith("```json")) text = text.substring(7).trim(); else if (text.startsWith("```")) text = text.substring(3).trim(); if (text.endsWith("```")) text = text.substring(0, text.length() - 3).trim(); return text; }
    private static String nullableText(JsonNode node, String field) { if (node == null || node.isMissingNode() || node.isNull()) return null; String value = node.path(field).asText(null); return value == null || value.isBlank() ? null : value.trim(); }
    private static Float nullableFloat(JsonNode node, String field) { JsonNode value = node == null ? null : node.get(field); return value == null || value.isNull() || !value.isNumber() ? null : (float) value.asDouble(); }
    private static Instant parseInstant(String value) { if (value == null || value.isBlank()) return null; try { return Instant.parse(value); } catch (DateTimeParseException ignored) { return null; } }
    private static List<String> stringList(JsonNode node) { if (node == null || !node.isArray()) return List.of(); List<String> result = new ArrayList<>(); for (JsonNode value : node) if (value.isTextual() && !value.asText().isBlank()) result.add(value.asText().trim()); return result; }
    private static String firstNonBlank(String first, String second) { return first != null && !first.isBlank() ? first : second; }

    public record ExtractionResult(ExperienceExtraction experience, List<InterviewQuestion> questions) {
        public ExtractionResult {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }

        public static ExtractionResult empty() {
            return new ExtractionResult(ExperienceExtraction.empty(), List.of());
        }
    }

    private record QuestionMetadata(
            String questionText,
            boolean isValidInterviewQuestion,
            String questionType,
            String difficulty,
            List<String> topics,
            String questionDescription,
            Float confidence) {
        private QuestionMetadata {
            topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }
}

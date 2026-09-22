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

    public OpenAiQuestionExtractor(ObjectMapper objectMapper, CrawlerSettings settings,
                                   @Value("${OPENAI_API_KEY:}") String apiKey) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.objectMapper = objectMapper;
        this.settings = settings;
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
            String prompt = """
                    You are an advanced technical interview data extraction engine.

                    The crawler has already fetched this interview-experience post and supplied its content below.
                    DO NOT browse the web, search, open URLs, or use tools.

                    SOURCE PLATFORM: %s
                    POST URL: %s
                    TITLE: %s
                    AUTHOR: %s
                    PUBLISHED AT: %s

                    PAGE CONTENT:
                    ---
                    %s
                    ---

                    STEP 1: AUTHENTICITY & COMPANY GATE
                    - Determine whether this is a REAL personal interview, assessment, or hiring experience.
                    - Reject tutorials, preparation articles, generic question lists, study guides, question banks,
                      practice problems, or generic interview advice unless they clearly contain a separate personal experience.
                    - A COMPANY MUST be explicitly supported by the supplied content or reliable metadata.
                    - Never guess a company from the technology, role, author, or question itself.
                    - Company names may appear in page content, title, tags, source metadata, or explicit company URLs.
                    - If no reliable company signal exists, return an empty questions list.

                    STEP 2: QUESTION EXTRACTION
                    Extract ONLY questions that were actually asked, or clearly named/described as an interview task
                    in the supplied experience.

                    CRITICAL:
                    - Do NOT create a question from generic statements such as "standard LeetCode tagged questions",
                      "LeetCode questions", "coding rounds", "technical discussion", or "project discussion".
                    - Do NOT invent a specific coding problem when the source does not identify one.
                    - If a round only says "Standard Leetcode tagged questions", extract NOTHING from that statement.
                    - If the source explicitly names or clearly describes a task, extract it even when details are sparse.
                      Example: "Design: Calendar" -> "Design a calendar."
                    - Preserve the source's level of specificity.
                    - Extract every distinct named/described technical question separately.
                    - Never merge separate questions.
                    - Never split one question merely because it has multiple requirements.

                    QUESTION TEXT:
                    - One concise line, preferably one sentence, <= 140 characters.
                    - Represent the actual technical task, not interview narrative.
                    - Remove filler such as "they asked me", "one question was", "the question was", "a variation of".
                    - Do not add "(Coding)", "(System Design)", "(Product)", "Variant", or similar labels.
                    - If the source explicitly names the problem, preserve that name.
                    - If only a descriptive task is provided, use only the information in the source.
                    - Never infer or substitute a canonical problem name from general knowledge.

                    QUESTION DESCRIPTION:
                    - Be elaborative and source-grounded.
                    - Preserve ALL useful technical details explicitly present in the source.
                    - Include requirements, inputs, outputs, constraints, edge cases, clarifications, follow-ups,
                      approaches explicitly discussed, complexity observations, and trade-offs when they are relevant.
                    - Do not force a short word limit.
                    - Prefer several concise sentences when the source contains useful detail.
                    - If the source only provides a short task name/topic, keep the description short and faithful.
                    - Never invent constraints, examples, algorithms, solutions, scale, APIs, storage, traffic,
                      or other requirements.
                    - Do not use general knowledge to fill missing details.
                    - Do not turn a vague topic into a detailed hypothetical problem.
                    - The description must describe the same actual question as questionText.

                    EVIDENCE REQUIREMENT:
                    Include an item only when you can point to specific supplied content showing that it was an
                    actual technical question/problem in the author's experience.
                    - "Standard Leetcode tagged questions" -> NOT a question.
                    - "Past project architecture discussion" -> NOT a question unless a concrete technical task is given.
                    - "Design: Calendar" -> IS a question/task.
                    - A named coding problem -> IS a question.
                    - A concrete system design prompt -> IS a question.

                    PROBLEM URL:
                    - problemUrl is ONLY the URL of the actual problem/question, never the interview-experience post URL.
                    - Inspect visible URLs, markdown links, HTML anchors, and URLs associated with the specific question.
                    - Preserve the exact direct problem URL when supplied.
                    - Never copy POST URL into problemUrl.
                    - Never construct, guess, infer, or search for a problem URL.
                    - If no direct problem URL is present, use null.

                    METADATA:
                    - sourcePlatform comes from the supplied source metadata.
                    - postDate comes from the supplied publication timestamp when available.
                    - role, level, location, candidateYoE, outcome, and roundType must be supported by the supplied content.
                    - difficulty is Easy, Medium, or Hard only when supported; otherwise null.
                    - topics should contain only topics supported by the source.
                    - confidence is 0.0-1.0 and reflects extraction confidence, not problem difficulty.

                    STRICT PROHIBITIONS:
                    - Never invent questions or metadata.
                    - Never use external knowledge to fill gaps.
                    - Never turn generic categories into specific questions.
                    - Never copy interview narrative into questionText.
                    - Never use the post URL as problemUrl.

                    QUALITY TEST:
                    Before including each question, ask:
                    1. Can I point to specific supplied text showing this was an actual technical question/task?
                    2. Does questionText describe that actual task rather than narrative or a generic category?
                    3. Is every detail in questionDescription supported by the supplied content?
                    If any answer is NO, do not include the unsupported question/detail.

                    Return ONLY valid JSON matching the required schema.
                    """.formatted(
                    source.getName(),
                    postUrl,
                    title,
                    author,
                    publishedAt,
                    truncate(bodyText)
            );

            log.info("""
                    ==================== OPENAI EXTRACTION PROMPT ====================
                    source={}
                    postUrl={}
                    {}
                    ================== END OPENAI EXTRACTION PROMPT ==================
                    """, source.getSlug(), postUrl, prompt);
            
            JsonNode root = callOpenAi(prompt, questionExtractionSchema(), source);
            String output = extractOutputText(root);
            if (output == null || output.isBlank()) {
                return Collections.emptyList();
            }

            ExtractedQuestions extracted = objectMapper.readValue(cleanJson(output), ExtractedQuestions.class);
            if (extracted.questions() == null) {
                return Collections.emptyList();
            }

            List<InterviewQuestion> questions = new ArrayList<>();
            for (ExtractedQuestion item : extracted.questions()) {
                if (item == null || item.questionText() == null || item.questionText().isBlank()) {
                    continue;
                }
                if (item.company() == null || item.company().isBlank()) {
                    log.info("Rejecting extracted question without company: source={}, postUrl={}",
                            source.getSlug(), postUrl);
                    continue;
                }

                InterviewQuestion question = new InterviewQuestion();
                question.setSourcePlatform(firstNonBlank(item.sourcePlatform(), source.getName()));
                question.setOriginalPostUrl(postUrl);
                question.setProblemUrl(normalizeProblemUrl(item.problemUrl()));
                question.setPostDate(item.postDate() != null
                        ? item.postDate()
                        : publishedAt == null ? null : publishedAt.atZone(ZoneOffset.UTC).toLocalDate());
                question.setCompany(item.company());
                question.setRole(item.role());
                question.setLevel(item.level());
                question.setLocation(item.location());
                question.setCandidateYoE(item.candidateYoE());
                question.setOutcome(item.outcome());
                question.setRoundType(item.roundType());
                question.setQuestionType(item.questionType());
                question.setQuestionText(item.questionText().trim());
                question.setQuestionDescription(item.questionDescription());
                                question.setDifficulty(item.difficulty());
                question.setTopics(item.topics() == null ? Collections.emptyList() : item.topics());
                question.setConfidence(item.confidence());
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
        properties.set("role", nullableStringSchema());
        properties.set("level", nullableStringSchema());
        properties.set("location", nullableStringSchema());
        properties.set("candidateYoE", nullableNumberSchema());
        properties.set("outcome", nullableStringSchema());
        properties.set("roundType", nullableStringSchema());
        properties.set("questionType", nullableStringSchema());
        properties.set("questionText", nullableStringSchema());
        properties.set("questionDescription", nullableStringSchema());
                properties.set("difficulty", nullableStringSchema());
        ObjectNode topicsSchema = objectMapper.createObjectNode().put("type", "array");
        topicsSchema.set("items", objectMapper.createObjectNode().put("type", "string"));
        properties.set("topics", topicsSchema);
        properties.set("confidence", nullableNumberSchema());
        question.set("properties", properties);
        question.set("required", objectMapper.createArrayNode()
                .add("sourcePlatform").add("problemUrl").add("postDate").add("company").add("role").add("level")
                .add("location").add("candidateYoE").add("outcome").add("roundType")
                .add("questionType").add("questionText").add("questionDescription")
                .add("difficulty").add("topics").add("confidence"));

        var schema = objectMapper.createObjectNode().put("type", "object").put("additionalProperties", false);
        ObjectNode schemaProperties = objectMapper.createObjectNode();
        ObjectNode questionsSchema = objectMapper.createObjectNode().put("type", "array");
        questionsSchema.set("items", question);
        schemaProperties.set("questions", questionsSchema);
        schema.set("properties", schemaProperties);
        schema.set("required", objectMapper.createArrayNode().add("questions"));
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

    private static String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private record ExtractedQuestions(List<ExtractedQuestion> questions) {}

    private record ExtractedQuestion(String sourcePlatform, String originalPostUrl, String problemUrl, LocalDate postDate,
                                     String company, String role, String level, String location, Float candidateYoE,
                                     String outcome, String roundType, String questionType, String questionText,
                                     String questionDescription, String difficulty,
                                     List<String> topics, Float confidence) {}
}

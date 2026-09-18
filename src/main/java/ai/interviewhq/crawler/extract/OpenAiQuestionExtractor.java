package ai.interviewhq.crawler.extract;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class OpenAiQuestionExtractor {
    private static final Logger log = LoggerFactory.getLogger(OpenAiQuestionExtractor.class);
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final CrawlerSettings settings;
    private final String apiKey;
    private final Environment environment;

    public OpenAiQuestionExtractor(ObjectMapper objectMapper, CrawlerSettings settings,
                                   Environment environment,
                                   @Value("${OPENAI_API_KEY:}") String apiKey) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
        this.objectMapper = objectMapper;
        this.settings = settings;
        this.environment = environment;
        this.apiKey = apiKey;
    }

    public List<InterviewQuestion> extract(ParsedEntry entry, Integer postId) {
        if (entry.url() == null || entry.url().isBlank()) return Collections.emptyList();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured");
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", settings.extractModel());
            request.put("input", buildPrompt(entry));
            request.put("max_output_tokens", settings.extractMaxTokens());

            ObjectNode reasoning = objectMapper.createObjectNode();
            reasoning.put("effort", "low");
            request.set("reasoning", reasoning);

            ArrayNode tools = objectMapper.createArrayNode();
            ObjectNode webSearch = objectMapper.createObjectNode();
            webSearch.put("type", "web_search");
            tools.add(webSearch);
            request.set("tools", tools);

            request.set("text", structuredOutputSchema());

            String requestBody = request.toString();

            HttpRequest httpRequest = HttpRequest.newBuilder(RESPONSES_URI)
                    .timeout(Duration.ofSeconds(120))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (isDevOrLocalProfile()) {
                log.info("OpenAI raw response: postId={}, model={}, status={}, body={}",
                        postId, settings.extractModel(), response.statusCode(), response.body());
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("OpenAI API request failed: HTTP "
                        + response.statusCode() + " - " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            if ("incomplete".equals(root.path("status").asText())) {
                String reason = root.path("incomplete_details").path("reason").asText("unknown");
                throw new IllegalStateException("OpenAI response incomplete: reason=" + reason);
            }

            String output = extractOutputText(response.body());
            if (output == null || output.isBlank()) return Collections.emptyList();

            ExtractedQuestions extracted;
            try {
                extracted = objectMapper.readValue(cleanJson(output), ExtractedQuestions.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException parseError) {
                String preview = cleanJson(output);
                if (preview.length() > 1500) preview = preview.substring(0, 1500) + "...<truncated>";
                throw new IllegalStateException("OpenAI returned invalid structured JSON: " + preview, parseError);
            }

            List<InterviewQuestion> questions = new ArrayList<>();
            if (extracted.questions() == null) return questions;

            for (ExtractedQuestion item : extracted.questions()) {
                if (item == null || item.questionText() == null || item.questionText().isBlank()) continue;

                InterviewQuestion question = new InterviewQuestion();
                question.setPostId(postId);
                question.setOriginalPostUrl(entry.url());
                question.setProblemUrl(normalizeProblemUrl(item.problemUrl()));
                question.setCompany(firstNonBlank(item.company(), entry.rawCompany()));
                question.setRole(firstNonBlank(item.role(), entry.rawRole()));
                question.setLevel(item.level());
                question.setRoundType(item.roundType());
                question.setQuestionType(item.questionType());
                question.setQuestionText(item.questionText().trim());
                question.setDifficulty(item.difficulty());
                question.setTopics(item.topics() == null ? Collections.emptyList() : item.topics());
                question.setConfidence(item.confidence());
                question.setModelName(settings.extractModel());
                question.setExtractedAt(Instant.now());
                question.setDedupeHash(Hashing.questionDedupeHash(question.getCompany(), question.getQuestionText()));
                questions.add(question);
            }
            return questions;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI extraction interrupted", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException illegalStateException
                    && illegalStateException.getMessage() != null
                    && illegalStateException.getMessage().startsWith("OpenAI")) {
                throw illegalStateException;
            }
            throw new IllegalStateException("Unable to extract interview questions with OpenAI", e);
        }
    }

    private boolean isDevOrLocalProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("dev".equals(profile) || "local".equals(profile)) return true;
        }
        return false;
    }

    private JsonNode structuredOutputSchema() {
        var format = objectMapper.createObjectNode()
                .put("type", "json_schema")
                .put("name", "interview_question_extraction")
                .put("strict", true);

        var question = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        var properties = objectMapper.createObjectNode();
        properties.set("originalPostUrl", nullableStringSchema());
        properties.set("problemUrl", nullableStringSchema());
        properties.set("company", nullableStringSchema());
        properties.set("role", nullableStringSchema());
        properties.set("level", nullableStringSchema());
        properties.set("roundType", nullableStringSchema());
        properties.set("questionType", nullableStringSchema());
        properties.set("questionText", nullableStringSchema());
        properties.set("difficulty", nullableStringSchema());
        properties.set("topics", objectMapper.createObjectNode()
                .put("type", "array")
                .set("items", objectMapper.createObjectNode().put("type", "string")));
        properties.set("confidence", objectMapper.createObjectNode()
                .set("type", objectMapper.createArrayNode().add("number").add("null")));
        question.set("properties", properties);
        question.set("required", requiredFields());

        var schema = objectMapper.createObjectNode()
                .put("type", "object")
                .put("additionalProperties", false);
        schema.set("properties", objectMapper.createObjectNode()
                .set("questions", objectMapper.createObjectNode()
                        .put("type", "array")
                        .set("items", question)));
        schema.set("required", objectMapper.createArrayNode().add("questions"));
        format.set("schema", schema);

        return objectMapper.createObjectNode().set("format", format);
    }

    private JsonNode requiredFields() {
        return objectMapper.createArrayNode()
                .add("originalPostUrl").add("problemUrl").add("company").add("role").add("level").add("roundType")
                .add("questionType").add("questionText").add("difficulty")
                .add("topics").add("confidence");
    }

    private JsonNode nullableStringSchema() {
        return objectMapper.createObjectNode()
                .set("type", objectMapper.createArrayNode().add("string").add("null"));
    }

    private String buildPrompt(ParsedEntry entry) {
        return """
                You are the interview-question extraction engine for InterviewHQ.

                Analyze the single public URL provided below.

                IMPORTANT TIME WINDOW:
                Extract only interview questions that were posted, asked, or documented within the last 48 hours relative to the current date/time.
                Use the publication timestamp or other explicit date/time information on the source page when available.
                Do not assume that an old interview experience is recent merely because the page is currently accessible.
                If the source does not provide enough information to establish that the content falls within the last 48 hours, do not include it.
                The 48-hour rule applies to the source content/post, not to the date of the interview unless the page clearly indicates that the interview itself occurred within that window.

                IMPORTANT RULES:

                1. Read and analyze the actual page content.
                2. Do not rely only on the page title, URL, metadata, snippets, or search-result text.
                3. Extract only questions that are explicitly present or clearly described in the page.
                4. Do not invent, reconstruct, or infer a question that is not supported by the page.
                5. Preserve the original technical meaning and important details of each question.
                6. If the page contains multiple distinct questions, extract every qualifying question.
                7. If the same question appears multiple times on the page, return it only once.
                8. If the page contains no qualifying interview or technical questions, return an empty questions array.
                9. Do not treat general discussion, opinions, preparation advice, or statements about technologies as interview questions unless they describe an actual question asked or problem given.
                10. A coding problem counts as an interview question when the page indicates that it was asked or given as part of an interview.
                11. If a specific coding problem can be confidently identified, provide its canonical official problem URL. Otherwise, set problemUrl to null.
                12. Do not guess company, role, level, round, difficulty, or topic. Use null or an empty array when the information is not supported by the page.
                13. Confidence must reflect how strongly the page supports the extracted question and its metadata.
                14. Do not include content older than 48 hours, even if it is otherwise relevant.
                15. If the page is a listing/index page, inspect the available individual entries and consider only entries whose source publication time is within the last 48 hours.

                QUESTION TYPES:
                Classify each extracted question as one of:
                - CODING
                - SYSTEM_DESIGN
                - LOW_LEVEL_DESIGN
                - BEHAVIORAL
                - TECHNICAL
                - DATABASE
                - DEVOPS
                - AI_ML
                - OTHER

                ROUND TYPES:
                Classify the interview round when supported by the page, for example:
                - OA
                - CODING
                - TECHNICAL
                - SYSTEM_DESIGN
                - LOW_LEVEL_DESIGN
                - MANAGERIAL
                - HR
                - BEHAVIORAL
                - PHONE_SCREEN
                - OTHER

                For every extracted question, return:
                - originalPostUrl
                - problemUrl
                - company
                - role
                - level
                - roundType
                - questionType
                - questionText
                - difficulty
                - topics
                - confidence

                QUESTION TEXT:
                The questionText should contain the actual interview question or problem being asked.
                For coding questions, include enough information to understand the problem, but do not unnecessarily reproduce long problem statements.
                For system-design questions, preserve the actual system/problem being requested.
                For behavioral questions, preserve the actual question.

                SOURCE URL:
                originalPostUrl must always be the exact URL provided below.

                PROBLEM URL:
                Use the canonical official problem URL only when the specific problem can be identified confidently.
                For example, if the page clearly refers to a specific LeetCode problem, use:
                https://leetcode.com/problems/<problem-slug>/
                Do not create a problem URL based only on a vague similarity.

                OUTPUT:
                Return only the structured JSON object matching the provided schema.

                URL TO ANALYZE:
                """ + entry.url();
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

    private static String normalizeProblemUrl(String value) {
        if (value == null || value.isBlank()) return null;
        String url = value.trim();
        if (!url.startsWith("https://leetcode.com/problems/")) return url;
        int query = url.indexOf("?");
        int fragment = url.indexOf("#");
        int end = url.length();
        if (query >= 0) end = Math.min(end, query);
        if (fragment >= 0) end = Math.min(end, fragment);
        return url.substring(0, end);
    }

    private static String firstNonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
    
    private record ExtractedQuestions(List<ExtractedQuestion> questions) {}

    private record ExtractedQuestion(String originalPostUrl, String problemUrl, String company, String role, String level, String roundType,
                                     String questionType, String questionText, String difficulty,
                                     List<String> topics, Float confidence) {}
}

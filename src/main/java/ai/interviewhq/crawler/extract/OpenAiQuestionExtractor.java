package ai.interviewhq.crawler.extract;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.domain.CrawlSource;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
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

    public List<InterviewQuestion> extract(CrawlSource source) {
        if (source == null || source.getName() == null || source.getName().isBlank()) {
            return Collections.emptyList();
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("OPENAI_API_KEY is not configured");
        }

        try {
            ObjectNode request = objectMapper.createObjectNode();
            request.put("model", settings.extractModel());
            request.put("input", buildPrompt(source));
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

            HttpRequest httpRequest = HttpRequest.newBuilder(RESPONSES_URI)
                    .timeout(Duration.ofSeconds(180))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(request.toString()))
                    .build();

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            if (isDevOrLocalProfile()) {
                log.info("OpenAI discovery response: source={}, model={}, status={}, body={}",
                        source.getSlug(), settings.extractModel(), response.statusCode(), response.body());
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
                question.setSourcePlatform(firstNonBlank(item.sourcePlatform(), source.getName()));
                question.setOriginalPostUrl(item.originalPostUrl());
                question.setProblemUrl(normalizeProblemUrl(item.problemUrl()));
                question.setPostDate(item.postDate());
                question.setCompany(item.company());
                question.setRole(item.role());
                question.setLevel(item.level());
                question.setLocation(item.location());
                question.setCandidateYoE(item.candidateYoE());
                question.setOutcome(item.outcome());
                question.setRoundType(item.roundType());
                question.setQuestionType(item.questionType());
                question.setQuestionText(item.questionText().trim());
                question.setCandidateApproach(item.candidateApproach());
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
            throw new IllegalStateException("Unable to discover interview questions with OpenAI", e);
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
        properties.set("sourcePlatform", nullableStringSchema());
        properties.set("originalPostUrl", nullableStringSchema());
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
        properties.set("candidateApproach", nullableStringSchema());
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
                .add("sourcePlatform").add("originalPostUrl").add("problemUrl").add("postDate")
                .add("company").add("role").add("level").add("location").add("candidateYoE").add("outcome")
                .add("roundType").add("questionType").add("questionText").add("candidateApproach")
                .add("difficulty").add("topics").add("confidence");
    }

    private JsonNode nullableNumberSchema() {
        return objectMapper.createObjectNode()
                .set("type", objectMapper.createArrayNode().add("number").add("null"));
    }

    private JsonNode nullableStringSchema() {
        return objectMapper.createObjectNode()
                .set("type", objectMapper.createArrayNode().add("string").add("null"));
    }

    private String buildPrompt(CrawlSource source) {
        Instant now = Instant.now();
        Instant cutoff = now.minusSeconds(settings.lookbackHours() * 3600L);

        return """
                You are InterviewHQ's interview-question discovery and extraction engine.

                Your task is to discover NEW public interview experiences and interview questions
                from the source specified below, using web search.

                SOURCE PLATFORM:
                %s

                SOURCE URL / SEARCH SCOPE:
                %s

                CURRENT UTC TIME:
                %s

                LOOKBACK WINDOW:
                %d hours

                IMPORTANT:
                This is a discovery task. Do NOT wait for the application to provide individual
                posts. Use web search yourself to find recent public posts on the specified source.

                DISCOVERY RULES:
                1. Search the specified source directly and independently.
                2. Search for interview experiences, coding questions, DSA questions, system design,
                   low-level design, behavioral, technical and company-specific interview reports.
                3. Perform multiple targeted searches where useful to obtain broad coverage.
                4. Prefer original posts over reposts, aggregators and search-result summaries.
                5. Open/read the actual public post whenever possible.
                6. Do not rely solely on search-result snippets.
                7. Extract every distinct qualifying interview question from every qualifying post.
                8. A single post can produce multiple InterviewQuestion records.
                9. Deduplicate repeated questions within the same post and across discovered posts.
                10. Do not invent questions or metadata.

                TIME WINDOW:
                Only include posts published within the last %d hours.
                The acceptable publication window is:
                %s through %s UTC.
                Prefer an explicit publication timestamp from the source.
                If the publication time cannot be established with reasonable confidence,
                do not include the post.
                Do not include an old post merely because it appeared in a recent search result.

                SOURCE-SPECIFIC SEARCH:
                Search the source named above, not all sources. The application will run this
                extraction separately for every configured source.

                EXTRACT:
                sourcePlatform
                originalPostUrl
                problemUrl
                postDate
                company
                role
                level
                location
                candidateYoE
                outcome
                roundType
                questionType
                difficulty
                topics
                questionText
                candidateApproach
                confidence

                FIELD RULES:
                - originalPostUrl must be the actual public post URL.
                - postDate is the post publication date as YYYY-MM-DD.
                - candidateYoE means the candidate's own stated years of experience.
                - Never confuse job requirements with candidate YoE.
                - outcome must be based on the candidate's stated result.
                - candidateApproach must only summarize an approach actually described.
                - problemUrl should be populated only when the specific official problem
                  can be identified confidently.
                - Use null for unsupported metadata.
                - Confidence must reflect evidence quality.
                - Preserve the actual technical meaning of the question.
                - Ignore generic career advice, job advertisements without interview content,
                  unrelated discussions and content outside the time window.
                - Never fabricate content that is inaccessible.

                QUESTION TYPES:
                CODING, SYSTEM_DESIGN, LOW_LEVEL_DESIGN, BEHAVIORAL, TECHNICAL,
                DATABASE, DEVOPS, AI_ML, OTHER

                ROUND TYPES:
                OA, CODING, TECHNICAL, SYSTEM_DESIGN, LOW_LEVEL_DESIGN, MANAGERIAL,
                HR, BEHAVIORAL, PHONE_SCREEN, OTHER

                OUTPUT:
                Return only the structured JSON object matching the supplied schema.
                If no qualifying posts/questions are found, return {"questions":[]}.

                """.formatted(
                source.getName(),
                source.getUrl(),
                now,
                settings.lookbackHours(),
                settings.lookbackHours(),
                cutoff.atOffset(ZoneOffset.UTC),
                now.atOffset(ZoneOffset.UTC)
        );
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

    private record ExtractedQuestion(String sourcePlatform, String originalPostUrl, String problemUrl, LocalDate postDate,
                                     String company, String role, String level, String location, Float candidateYoE,
                                     String outcome, String roundType, String questionType, String questionText,
                                     String candidateApproach, String difficulty, List<String> topics, Float confidence) {}
}

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
                You are the InterviewHQ extraction engine. Extract genuine software engineering
                interview questions from '%s' (Platform: '%s') published within the last %d hours
                relative to '%s'.

                DISCOVERY & ELIGIBILITY
                - Strict Time Window: Eligibility is based on the content's publication date,
                  NOT the interview date. Exclude old posts bumped by recent comments, indexing,
                  or discovery algorithms.
                - Content Scope: Discover relevant public posts on the specified source only.
                  Exclude generic advice, job ads, preparation lists, and unrelated technical
                  discussions.
                - Source Verification: Base extraction on the actual content, not just titles
                  or snippets. Prefer the original source over aggregators.
                - Publication Date: Verify the publication date from the original source whenever
                  possible. If the publication date cannot be established with reasonable
                  confidence, exclude the content.

                EXTRACTION CONSTRAINTS
                - Zero Hallucination: Never invent, infer, or generate unsupported questions,
                  metadata, job details, or outcomes. If a field is unsupported, use null.
                  Accuracy > Quantity.
                - Record Granularity: Extract every distinct question as a separate record.
                  Do not infer a question merely because a technology/topic is mentioned.
                - Deduplication: Deduplicate identical questions unless they originate from
                  materially different interview experiences. Questions from the same post share
                  the same originalPostUrl.
                - Question Preservation:
                  * Coding: Summarize long prompts while keeping essential requirements,
                    constraints, and tasks.
                  * Behavioral/System/Technical: Preserve the specific question or system asked;
                    do not replace it with a generic category.
                - Candidate Approach: Must be a faithful summary of the candidate's explicitly
                  stated reasoning/solution. Never generate a hypothetical solution. If absent,
                  use null.

                JSON SCHEMA & FIELD RULES
                Extract these fields (use null if unsupported):
                - sourcePlatform: The platform containing the original post.
                - originalPostUrl: Direct URL to the specific post containing the interview
                  information, NOT a homepage, tag, subreddit, search page, or aggregator.
                - problemUrl: Canonical official problem link, if confidently identified.
                - postDate: Publication date of the original post (YYYY-MM-DD).
                - company: Normalize obvious naming variations only when the company identity
                  is unambiguous.
                - role: Explicitly stated interview role.
                - level: Explicitly stated interview/job level.
                - location: Explicitly stated interview/job location.
                - candidateYoE: Candidate's stated professional experience. NEVER use the job
                  description's required experience.
                - outcome: Explicitly stated outcome, e.g. Offer, Rejected, No Offer, In Progress.
                - roundType: The specific interview round in which the question was asked.
                - questionType: Must be CODING, SYSTEM_DESIGN, LOW_LEVEL_DESIGN, BEHAVIORAL,
                  TECHNICAL, DATABASE, DEVOPS, AI_ML, or OTHER.
                - difficulty: Easy, Medium, or Hard only if explicitly stated or directly
                  supported.
                - topics: Array of explicit technical concepts relevant to the actual question.
                - questionText: The actual interview question or summarized problem.
                - candidateApproach: Faithful summary of the candidate's approach, or null.
                - confidence: Float from 0.0 to 1.0 representing the strength of source evidence.

                OUTPUT
                Return STRICTLY a JSON object matching the supplied schema. Do not output markdown,
                explanations, citations, or conversational text outside the JSON.

                {
                  "questions": [...]
                }

                Return an empty array if no qualifying questions are found:
                {
                  "questions": []
                }

                SOURCE:
                %s

                PLATFORM:
                %s

                CURRENT TIME:
                %s

                LOOKBACK HOURS:
                %d

                ELIGIBILITY WINDOW START:
                %s
                """.formatted(
                source.getUrl(),
                source.getName(),
                settings.lookbackHours(),
                now,
                settings.lookbackHours(),
                source.getUrl(),
                source.getName(),
                now,
                settings.lookbackHours(),
                cutoff
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

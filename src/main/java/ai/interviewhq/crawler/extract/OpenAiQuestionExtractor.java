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
                    You are an advanced technical interview question and problem listing engine.

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

                    EXPERIENCE AUTHENTICITY GATE:
                    First determine whether this post describes a REAL interview, assessment, or hiring interaction
                    experienced by the author/candidate.

                    Extract technical questions ONLY when they are reported as having been asked, given, or
                    encountered by the candidate during their own interview or assessment.

                    DO NOT extract questions from:
                    - interview preparation articles
                    - "Top X interview questions" lists
                    - study guides
                    - tutorials or educational articles
                    - question banks or practice problems
                    - generic interview tips
                    - collections of commonly asked questions
                    - posts that provide questions and answers without describing the author's actual interview experience

                    Strong signals that the post IS an interview experience include:
                    - "I interviewed at..."
                    - "My interview experience..."
                    - "I was asked..."
                    - "The interviewer asked..."
                    - "In the coding round..."
                    - "In the system design round..."
                    - "During my interview..."
                    - interview rounds, dates, companies, roles, outcomes, or candidate experience

                    If the post is primarily an educational/question-list article rather than a personal interview
                    experience, return an empty questions list.

                    If the title or content indicates a generic collection such as "Top 50 Interview Questions",
                    "100 Java Interview Questions", "Frequently Asked Questions", "Interview Questions with Answers",
                    or similar educational content, treat it as a preparation article unless the post clearly contains
                    a separate personal interview experience.

                    Your task is to generate a list of genuine technical interview questions, coding problems,
                    system design prompts, or technical scenarios reported in this text.

                    IMPORTANT:
                    You are listing the ACTUAL CORE TECHNICAL QUESTION OR PROBLEM, not blindly copying sentences
                    from the post.

                    For every candidate item, determine:
                    1. What was actually asked or given to the candidate?
                    2. What is the core technical problem or prompt?
                    3. Which words are merely describing, qualifying, comparing, or explaining that question?

                    CORE PROBLEM EXTRACTION:
                    Separate the source text into:
                    A. The actual technical problem/question
                    B. Description or qualification of that problem
                    C. Surrounding interview narrative

                    questionText must represent A.
                    Use B only to accurately describe A.
                    Never include C in questionText.

                    QUESTION NORMALIZATION RULES:
                    - List the smallest meaningful description that identifies the actual technical question or problem.
                    - Preserve the original meaning and technical context.
                    - Do not invent information that is not present in the post.
                    - Remove conversational prefixes, filler phrases, and qualifiers when they do not form part
                      of the actual problem identity.
                    - Remove conversational qualifiers such as "a variation of", "a modified version of",
                      "a variant of", "similar to", "based on", "something like", "one question was",
                      "the question was", "they asked me", "was asked", and "just explanation".
                    - Preserve technical constraints, requirements, data structures, scale requirements, and other
                      details that materially describe the problem.

                    However:
                    - Do NOT remove information that changes the identity of the problem.
                    - Do NOT assume that two problems are the same just because they sound similar.
                    - Do NOT replace a problem with a standard/canonical name based on similarity.
                    - Do NOT use external knowledge to determine what the author "must have meant".
                    - When the author gives only a descriptive technical problem statement, preserve that description.
                    - When the author explicitly names a problem, prefer the explicit problem name.

                    Examples:
                    "the coding question was a variation of Two Sum with negative numbers"
                    → "Two Sum with negative numbers"

                    "they asked to design a notification system like Kafka-based pub-sub"
                    → "Design a Kafka-based pub-sub notification system"

                    "question was similar to Longest Substring Without Repeating Characters"
                    → "Longest Substring Without Repeating Characters"

                    "Past project architecture discussion"
                    → DO NOT list as a technical question unless the post explicitly describes a concrete
                      technical question or problem that was asked about the architecture.

                    "Discussed microservices and Docker"
                    → DO NOT list as a question unless the post explicitly identifies a technical interview
                      question or problem involving them.

                    EVIDENCE REQUIREMENT:
                    Include an item only when the post provides sufficient evidence that it was part of an actual
                    technical interview or assessment.

                    Valid evidence includes:
                    - "they asked..."
                    - "the coding problem was..."
                    - "I was asked to write a function..."
                    - "system design round: ..."
                    - "coding round: ..."
                    - a clearly described sequence of questions from the author's own interview.

                    Do NOT list:
                    - interview preparation advice
                    - technologies or frameworks merely mentioned
                    - candidate background/previous projects without a concrete technical prompt
                    - hypothetical examples or generic skills
                    - recruiter/screening questions unless they contain an actual technical problem

                    MULTIPLE QUESTIONS:
                    - List every distinct technical question/problem separately.
                    - Do not merge different questions.
                    - Do not split one question into multiple questions merely because it contains multiple
                      requirements or constraints.

                    CANONICAL NAME RULE:
                    - If the post explicitly names the problem, preserve that exact problem name.
                    - If the post describes a known problem but does not explicitly provide its name, DO NOT infer
                      or substitute a canonical LeetCode, GFG, HackerRank, or other platform problem name.
                    - If the post says "variation of N-Anagram", list "N-Anagram".
                    - If the post says "similar to Two Sum with negative numbers", list
                      "Two Sum with negative numbers".
                    - If the post only provides a descriptive problem statement, create a concise description
                      using ONLY information contained in the supplied content.
                    - Never hallucinate or rename the author's problem based on external knowledge.

                    QUALITY TEST:
                    Before including each technical question, ask internally:
                    "Could I point to a specific part of the supplied post that shows it was an actual technical
                    question or problem?"
                    If NO → do not list it.

                    Then ask:
                    "Does questionText describe the actual technical problem, rather than the author's narrative?"
                    If NO → normalize it before adding it to the list.

                    Rules:
                    - Never invent a question or metadata.
                    - Use null when metadata is not supported by the content.
                    - questionType: CODING, SYSTEM_DESIGN, LOW_LEVEL_DESIGN, BEHAVIORAL, TECHNICAL, DATABASE, DEVOPS, AI_ML, or OTHER.
                    - difficulty: Easy, Medium, or Hard only when supported.
                    - candidateApproach must only contain the candidate's explicitly stated approach.
                    - candidateYoE must come from the candidate's content.
                    - problemUrl only when confidently identified in the supplied content.
                    - postDate should use the supplied publication timestamp converted to UTC date when available.
                    - confidence must be between 0.0 and 1.0.

                    Return ONLY the required JSON object.
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
                question.setCandidateApproach(item.candidateApproach());
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
        properties.set("candidateApproach", nullableStringSchema());
        properties.set("difficulty", nullableStringSchema());
        ObjectNode topicsSchema = objectMapper.createObjectNode().put("type", "array");
        topicsSchema.set("items", objectMapper.createObjectNode().put("type", "string"));
        properties.set("topics", topicsSchema);
        properties.set("confidence", nullableNumberSchema());
        question.set("properties", properties);
        question.set("required", objectMapper.createArrayNode()
                .add("sourcePlatform").add("problemUrl").add("postDate").add("company").add("role").add("level")
                .add("location").add("candidateYoE").add("outcome").add("roundType")
                .add("questionType").add("questionText").add("candidateApproach")
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
                                     String candidateApproach, String difficulty, List<String> topics, Float confidence) {}
}

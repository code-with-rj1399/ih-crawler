package ai.interviewhq.crawler.extract;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import ai.interviewhq.crawler.util.Hashing;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

@Service
public class OpenAiQuestionExtractor {
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final CrawlerSettings settings;

    public OpenAiQuestionExtractor(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper, CrawlerSettings settings) {
        this.chatClient = chatClientBuilder
                .defaultOptions(org.springframework.ai.openai.OpenAiChatOptions.builder()
                        .model(settings.extractModel())
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.settings = settings;
    }

    public InterviewQuestion extract(ParsedEntry entry, Integer postId) {
        String text = entry.bodyText();
        if (text == null || text.isBlank()) return null;

        String prompt = "Extract ONE software engineering interview question from the content below.\\n"
                + "If the content does not contain an interview question, return {\\\"questionText\\\":null}.\\n"
                + "Do not invent information.\\n\\n"
                + "Return JSON only: {\\n"
                + "  \\\"company\\\": \\\"string or null\\\",\\n"
                + "  \\\"role\\\": \\\"string or null\\\",\\n"
                + "  \\\"level\\\": \\\"string or null\\\",\\n"
                + "  \\\"roundType\\\": \\\"string or null\\\",\\n"
                + "  \\\"questionType\\\": \\\"string or null\\\",\\n"
                + "  \\\"questionText\\\": \\\"string or null\\\",\\n"
                + "  \\\"difficulty\\\": \\\"string or null\\\",\\n"
                + "  \\\"topics\\\": [\\\"string\\\"],\\n"
                + "  \\\"confidence\\\": 0.0\\n}\\n\\n"
                + "Title: " + nullToEmpty(entry.title()) + "\\n"
                + "Company hint: " + nullToEmpty(entry.rawCompany()) + "\\n"
                + "Role hint: " + nullToEmpty(entry.rawRole()) + "\\n\\n"
                + "Content:\\n" + text;

        String response = chatClient.prompt().user(prompt).call().content();
        if (response == null || response.isBlank()) return null;

        try {
            ExtractedQuestion extracted = objectMapper.readValue(cleanJson(response), ExtractedQuestion.class);
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
        } catch (Exception e) {
            throw new IllegalStateException("Unable to parse OpenAI extraction response", e);
        }
    }

    private static String cleanJson(String response) {
        String value = response.trim();
        if (value.startsWith("```")) {
            value = value.replaceFirst("^```(?:json)?\\\\s*", "");
            value = value.replaceFirst("\\\\s*```$", "");
        }
        return value.trim();
    }
    private static String firstNonBlank(String value, String fallback) { return value != null && !value.isBlank() ? value : fallback; }
    private static String nullToEmpty(String value) { return value == null ? "" : value; }
    private record ExtractedQuestion(String company, String role, String level, String roundType, String questionType, String questionText, String difficulty, List<String> topics, Float confidence) {}
}
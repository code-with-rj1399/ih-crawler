package ai.interviewhq.crawler.extract;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Holds the extraction prompt used by the crawler.
 *
 * The prompt can be changed at runtime from the dev dashboard for prompt
 * experimentation. Runtime changes are intentionally in-memory and reset on
 * application restart.
 */
@Service
public class ExtractionPromptService {
    private volatile String prompt;

    public ExtractionPromptService() {
        this.prompt = loadDefaultPrompt();
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("Extraction prompt cannot be blank");
        }
        this.prompt = normalizeGranularityField(prompt);
    }

    public void resetToDefault() {
        this.prompt = loadDefaultPrompt();
    }

    private static String loadDefaultPrompt() {
        try {
            String prompt = new String(
                    new ClassPathResource("prompts/interview_question_extraction.txt")
                            .getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            return normalizeGranularityField(prompt);
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load shared extraction prompt", e);
        }
    }

    /**
     * Keep runtime/default prompts aligned with the canonical questionGranularity field.
     * This also protects against stale prompt text during rolling deployments.
     */
    private static String normalizeGranularityField(String prompt) {
        return prompt
                .replace("questionSpecificity", "questionGranularity")
                .replace("QuestionSpecificity", "QuestionGranularity")
                .replace("specificity_rules", "granularity_rules");
    }
}

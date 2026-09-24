package ai.interviewhq.crawler.extract;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/** Runtime-editable prompts for the two-stage extraction pipeline. */
@Service
public class ExtractionPromptService {
    private volatile String experiencePrompt;
    private volatile String questionMetadataPrompt;

    public ExtractionPromptService() {
        resetToDefault();
    }

    public String getExperiencePrompt() { return experiencePrompt; }
    public String getQuestionMetadataPrompt() { return questionMetadataPrompt; }

    public synchronized void setExperiencePrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Experience extraction prompt cannot be blank");
        experiencePrompt = normalizeGranularityField(prompt);
    }

    public synchronized void setQuestionMetadataPrompt(String prompt) {
        if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("Question metadata prompt cannot be blank");
        questionMetadataPrompt = normalizeGranularityField(prompt);
    }

    public synchronized void resetToDefault() {
        experiencePrompt = loadDefaultPrompt("prompts/experience_extraction.txt");
        questionMetadataPrompt = loadDefaultPrompt("prompts/question_metadata_extraction.txt");
    }

    private static String loadDefaultPrompt(String path) {
        try {
            return normalizeGranularityField(new String(
                    new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to load extraction prompt: " + path, e);
        }
    }

    private static String normalizeGranularityField(String prompt) {
        return prompt
                .replace("questionSpecificity", "questionGranularity")
                .replace("QuestionSpecificity", "QuestionGranularity")
                .replace("specificity_rules", "granularity_rules");
    }
}

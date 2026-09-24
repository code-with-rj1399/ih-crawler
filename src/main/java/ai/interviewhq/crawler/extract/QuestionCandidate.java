package ai.interviewhq.crawler.extract;

/**
 * The intentionally small output of experience extraction.
 * Keep this model independent from question classification so future
 * extraction strategies can be added without changing persistence models.
 */
public record QuestionCandidate(String questionText) {
    public boolean isValid() {
        return questionText != null && !questionText.isBlank();
    }
}

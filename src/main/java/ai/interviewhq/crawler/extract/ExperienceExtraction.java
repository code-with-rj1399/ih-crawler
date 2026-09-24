package ai.interviewhq.crawler.extract;

import java.time.Instant;
import java.util.List;

/**
 * Step 1 result: interview-experience metadata plus question candidates.
 * Question-specific metadata is deliberately absent and is added by step 2.
 */
public record ExperienceExtraction(
        String title,
        String summary,
        Instant postedAt,
        String author,
        String company,
        String role,
        String level,
        String location,
        Float candidateYoE,
        String outcome,
        List<String> rounds,
        List<QuestionCandidate> questions,
        boolean authenticExperience
) {
    public ExperienceExtraction {
        rounds = rounds == null ? List.of() : List.copyOf(rounds);
        questions = questions == null ? List.of() : List.copyOf(questions);
    }
}

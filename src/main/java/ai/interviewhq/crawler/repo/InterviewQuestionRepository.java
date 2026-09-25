package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.InterviewQuestion;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class InterviewQuestionRepository extends DynamoRepository<InterviewQuestion, Integer> {
    public InterviewQuestionRepository(DynamoDbRepositorySupport db) {
        super(db);
    }

    public InterviewQuestion save(InterviewQuestion question) {
        if (question.getId() == null) question.setId(db.nextId("interview-question"));
        if (question.getCreatedAt() == null) question.setCreatedAt(java.time.Instant.now());
        if (question.getQuestionTypes() == null) question.setQuestionTypes(new java.util.ArrayList<>());
        if (question.getExperienceId() == null) throw new IllegalArgumentException("experienceId is required for an interview question");
        return db.save(question, "EXPERIENCE#" + question.getExperienceId(), "QUESTION#" + question.getDedupeHash());
    }

    public Optional<InterviewQuestion> findById(Integer id) {
        return findAll().stream().filter(e -> id.equals(e.getId())).findFirst();
    }

    public List<InterviewQuestion> findAll() {
        return db.scan(InterviewQuestion.class);
    }

    public List<InterviewQuestion> findByExperienceId(Integer experienceId) {
        if (experienceId == null) return List.of();
        return db.query(InterviewQuestion.class, "EXPERIENCE#" + experienceId).stream()
                .sorted(Comparator.comparing(
                        InterviewQuestion::getId,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    public int deleteByExperienceId(Integer experienceId) {
        if (experienceId == null) return 0;
        int deleted = 0;
        for (InterviewQuestion question : findByExperienceId(experienceId)) {
            if (question.getDedupeHash() != null) {
                db.delete("EXPERIENCE#" + experienceId, "QUESTION#" + question.getDedupeHash());
                deleted++;
            }
        }
        return deleted;
    }

    public Optional<InterviewQuestion> findByExperienceAndDedupeHash(Integer experienceId, String hash) {
        if (experienceId == null || hash == null || hash.isBlank()) return Optional.empty();
        return db.find(InterviewQuestion.class, "EXPERIENCE#" + experienceId, "QUESTION#" + hash);
    }

    public int deleteAll() {
        return db.deleteAllByEntityType(InterviewQuestion.class);
    }

    protected void deleteKey(Integer id) {
        findById(id).ifPresent(e -> db.delete("EXPERIENCE#" + e.getExperienceId(), "QUESTION#" + e.getDedupeHash()));
    }
}

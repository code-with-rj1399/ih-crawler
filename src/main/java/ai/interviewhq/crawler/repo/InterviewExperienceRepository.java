package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.InterviewExperience;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Repository
public class InterviewExperienceRepository extends DynamoRepository<InterviewExperience, Integer> {
    public InterviewExperienceRepository(DynamoDbRepositorySupport db) {
        super(db);
    }

    public InterviewExperience save(InterviewExperience experience) {
        if (experience.getId() == null) experience.setId(db.nextId("interview-experience"));
        if (experience.getCreatedAt() == null) experience.setCreatedAt(Instant.now());
        if (experience.getQuestionCount() == null) experience.setQuestionCount(0);
        return db.save(experience, "EXPERIENCE#" + experience.getDedupeHash(), "ENTITY");
    }

    public Optional<InterviewExperience> findById(Integer id) {
        return findAll().stream().filter(e -> id.equals(e.getId())).findFirst();
    }

    public Optional<InterviewExperience> findByDedupeHash(String hash) {
        return db.find(InterviewExperience.class, "EXPERIENCE#" + hash, "ENTITY");
    }

    public List<InterviewExperience> findAll() {
        return db.scan(InterviewExperience.class).stream()
                .sorted(Comparator.comparing(
                        InterviewExperience::getPostedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .reversed()
                        .thenComparing(
                                InterviewExperience::getId,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public int deleteAll() {
        return db.deleteAllByEntityType(InterviewExperience.class);
    }

    protected void deleteKey(Integer id) {
        findById(id).ifPresent(e -> db.delete("EXPERIENCE#" + e.getDedupeHash(), "ENTITY"));
    }
}

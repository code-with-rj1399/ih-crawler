package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.config.DynamoDbRepositorySupport;
import ai.interviewhq.crawler.domain.InterviewExperience;
import ai.interviewhq.crawler.domain.InterviewExperienceLookup;
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
        db.save(experience, "EXPERIENCE#" + experience.getId(), "ENTITY");
        db.save(new InterviewExperienceLookup(experience.getId(), experience.getDedupeHash()),
                "EXPERIENCE_DEDUPE#" + experience.getDedupeHash(), "ENTITY");
        return experience;
    }

    public Optional<InterviewExperience> findById(Integer id) {
        if (id == null) return Optional.empty();
        return db.find(InterviewExperience.class, "EXPERIENCE#" + id, "ENTITY");
    }

    public Optional<InterviewExperience> findByDedupeHash(String hash) {
        if (hash == null || hash.isBlank()) return Optional.empty();
        return db.find(InterviewExperienceLookup.class, "EXPERIENCE_DEDUPE#" + hash, "ENTITY")
                .flatMap(lookup -> findById(lookup.getExperienceId()));
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
        int deleted = db.deleteAllByEntityType(InterviewExperience.class);
        deleted += db.deleteAllByEntityType(InterviewExperienceLookup.class);
        return deleted;
    }

    protected void deleteKey(Integer id) {
        findById(id).ifPresent(e -> {
            db.delete("EXPERIENCE#" + e.getId(), "ENTITY");
            if (e.getDedupeHash() != null) db.delete("EXPERIENCE_DEDUPE#" + e.getDedupeHash(), "ENTITY");
        });
    }
}

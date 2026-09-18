package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.domain.InterviewQuestion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InterviewQuestionRepository extends JpaRepository<InterviewQuestion, Integer> {

    Optional<InterviewQuestion> findByDedupeHash(String dedupeHash);

    @Query("""
            select q from InterviewQuestion q
            where (:company is null or lower(q.company) = lower(:company))
            order by q.askedAt desc, q.id desc
            """)
    List<InterviewQuestion> search(@Param("company") String company, Pageable pageable);
}

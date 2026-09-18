package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.domain.InterviewPost;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InterviewPostRepository extends JpaRepository<InterviewPost, Integer> {

    Optional<InterviewPost> findBySourceIdAndUrl(Integer sourceId, String url);

    List<InterviewPost> findByExtractedFalseOrderByPostedAtDescIdDesc(Pageable pageable);
}

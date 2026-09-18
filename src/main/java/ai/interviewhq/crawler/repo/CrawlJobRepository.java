package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.domain.CrawlJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrawlJobRepository extends JpaRepository<CrawlJob, Integer> {

    boolean existsByStatus(String status);

    List<CrawlJob> findTop50ByOrderByCreatedAtDesc();
}

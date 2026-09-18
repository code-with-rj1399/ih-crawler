package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.domain.CrawlSource;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrawlSourceRepository extends JpaRepository<CrawlSource, Integer> {

    List<CrawlSource> findByEnabledTrueOrderByIdAsc();

    Optional<CrawlSource> findBySlug(String slug);
}

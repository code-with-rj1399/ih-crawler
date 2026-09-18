package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.domain.CrawlPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CrawlPageRepository extends JpaRepository<CrawlPage, Integer> {

    Optional<CrawlPage> findByUrl(String url);
}

package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.domain.CrawlerConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CrawlerConfigRepository extends JpaRepository<CrawlerConfig, String> {
}

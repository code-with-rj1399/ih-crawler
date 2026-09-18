package ai.interviewhq.crawler.repo;

import ai.interviewhq.crawler.domain.CrawlJobLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrawlJobLogRepository extends JpaRepository<CrawlJobLog, Integer> {

    List<CrawlJobLog> findByJobIdOrderByIdAsc(Integer jobId);
}

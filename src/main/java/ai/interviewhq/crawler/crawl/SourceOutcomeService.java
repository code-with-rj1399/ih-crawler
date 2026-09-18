package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Service
public class SourceOutcomeService {

    private static final Logger log = LoggerFactory.getLogger(SourceOutcomeService.class);
    private static final Set<Integer> BLOCK_STATUSES = Set.of(401, 403, 429);

    private final CrawlSourceRepository sourceRepository;
    private final CrawlerSettings settings;

    public SourceOutcomeService(CrawlSourceRepository sourceRepository, CrawlerSettings settings) {
        this.sourceRepository = sourceRepository;
        this.settings = settings;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(CrawlSource source, int status, String error, boolean success) {
        boolean blockish = BLOCK_STATUSES.contains(status);
        CrawlSource attached = sourceRepository.findById(source.getId()).orElse(source);
        attached.setLastCrawledAt(Instant.now());
        if (status > 0) {
            attached.setLastHttpStatus(status);
        }
        if (success) {
            attached.setLastSuccessAt(Instant.now());
            attached.setLastError(null);
            attached.setConsecutiveFailures(0);
            attached.setCircuitOpenUntil(null);
        } else {
            attached.setLastError(truncate(error, 1000));
            if (blockish) {
                int failures = attached.getConsecutiveFailures() + 1;
                attached.setConsecutiveFailures(failures);
                int threshold = Math.max(1, settings.defaults().getCircuitFailures());
                if (failures >= threshold) {
                    int minutes = Math.max(1, settings.defaults().getCircuitOpenMinutes());
                    Instant until = Instant.now().plus(Duration.ofMinutes(minutes));
                    attached.setCircuitOpenUntil(until);
                    log.warn("circuit open for {} until {} after {} consecutive 401/403/429",
                            attached.getSlug(), until, failures);
                }
            }
        }
        sourceRepository.save(attached);
        source.setLastCrawledAt(attached.getLastCrawledAt());
        source.setLastSuccessAt(attached.getLastSuccessAt());
        source.setLastHttpStatus(attached.getLastHttpStatus());
        source.setLastError(attached.getLastError());
        source.setConsecutiveFailures(attached.getConsecutiveFailures());
        source.setCircuitOpenUntil(attached.getCircuitOpenUntil());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}

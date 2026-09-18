package ai.interviewhq.crawler.config;

import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class DevDataSeeder implements CommandLineRunner {

    private final CrawlSourceRepository repository;
    private final boolean enabled;

    public DevDataSeeder(
            CrawlSourceRepository repository,
            @Value("${crawler.seed.leetcode.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled || repository.findBySlug("leetcode-interviews").isPresent()) {
            return;
        }

        CrawlSource source = new CrawlSource();
        source.setSlug("leetcode-interviews");
        source.setName("LeetCode Interview Experience");
        source.setUrl("https://leetcode.com/discuss/interview-experience/");
        source.setSourceKind("leetcode_discuss");
        source.setEnabled(true);
        source.setRateLimitRpm(30);
        source.setCrawlDelayMs(2000);
        source.setPerHostConcurrency(1);
        source.setRobotsMode("RESPECT");
        source.setCreatedAt(Instant.now());
        source.setUpdatedAt(Instant.now());

        repository.save(source);
    }
}

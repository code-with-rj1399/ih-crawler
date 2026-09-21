package ai.interviewhq.crawler.config;

import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.repo.CrawlSourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;

/**
 * Keeps DynamoDB sources in sync with {@link SeedCatalog} on every boot.
 * Existing rows are updated in place so URL/kind fixes actually take effect.
 */
@Component
public class DevDataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private final CrawlSourceRepository repository;
    private final boolean enabled;

    public DevDataSeeder(
            CrawlSourceRepository repository,
            @Value("${crawler.seed.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            return;
        }

        int created = 0;
        int updated = 0;
        int disabled = 0;
        for (SeedCatalog.Seed seed : SeedCatalog.all()) {
            Result result = upsert(seed);
            if (result == Result.CREATED) {
                created++;
            } else if (result == Result.UPDATED) {
                updated++;
            }
            if (!seed.enabled()) {
                disabled++;
            }
        }
        log.info("Seed catalog synced: created={}, updated={}, retiredInCatalog={}",
                created, updated, disabled);
    }

    private Result upsert(SeedCatalog.Seed seed) {
        CrawlSource source = repository.findBySlug(seed.slug()).orElse(null);
        Result result = source == null ? Result.CREATED : Result.UPDATED;
        if (source == null) {
            source = new CrawlSource();
            source.setSlug(seed.slug());
            source.setCreatedAt(Instant.now());
        }

        source.setName(seed.name());
        source.setUrl(seed.url());
        source.setSourceKind(seed.kind());
        source.setEnabled(seed.enabled());
        source.setRateLimitRpm(seed.rpm());
        source.setCrawlDelayMs(seed.delayMs());
        source.setPerHostConcurrency(1);
        source.setRobotsMode("honor");
        source.setNotes(seed.notes());
        source.setParserConfig(seed.parserConfig() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(seed.parserConfig()));
        source.setUpdatedAt(Instant.now());
        repository.save(source);
        return result;
    }

    private enum Result { CREATED, UPDATED }
}

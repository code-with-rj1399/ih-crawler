package ai.interviewhq.crawler.config;

import ai.interviewhq.crawler.domain.CrawlerConfig;
import ai.interviewhq.crawler.repo.CrawlerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class CrawlerSettings {

    public static final String CRON_ENABLED = "cron.enabled";
    public static final String CRON_INTERVAL_MINUTES = "cron.interval_minutes";
    public static final String CRAWL_LOOKBACK_HOURS = "crawl.lookback_hours";
    public static final String CRAWL_MAX_CONCURRENCY = "crawl.max_concurrency";
    public static final String CRAWL_MAX_CONCURRENT_TASKS = "crawl.max_concurrent_tasks";
    public static final String CRAWL_PER_HOST_CONCURRENCY = "crawl.per_host_concurrency";
    public static final String CRAWL_USER_AGENT = "crawl.user_agent";
    public static final String CRAWL_TIMEOUT_MS = "crawl.timeout_ms";
    public static final String CRAWL_MAX_BYTES = "crawl.max_bytes";
    public static final String CRAWL_MAX_RETRIES = "crawl.max_retries";
    public static final String EXTRACT_ENABLED = "extract.enabled";
    public static final String EXTRACT_MODEL = "extract.model";
    public static final String EXTRACT_MAX_POSTS = "extract.max_posts_per_job";
    public static final String EXTRACT_MAX_TOKENS = "extract.max_tokens";
    public static final String EXTRACT_MAX_POSTS_PER_SOURCE = "extract.max_posts_per_source";
    public static final String EXTRACT_MAX_QUESTIONS_PER_POST = "extract.max_questions_per_post";

    private static final Logger log = LoggerFactory.getLogger(CrawlerSettings.class);

    private final CrawlerConfigRepository repository;
    private final CrawlerProperties defaults;

    public CrawlerSettings(CrawlerConfigRepository repository, CrawlerProperties defaults) {
        this.repository = repository;
        this.defaults = defaults;
    }

    public String get(String key, String fallback) {
        try {
            return repository.findById(key)
                    .map(CrawlerConfig::getValue)
                    .filter(v -> v != null && !v.isBlank())
                    .orElse(fallback);
        } catch (RuntimeException ex) {
            log.warn("Failed to read crawler_config key {} ({}); using fallback", key, ex.getMessage());
            return fallback;
        }
    }

    public int getInt(String key, int fallback) {
        String raw = get(key, Integer.toString(fallback));
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            log.warn("crawler_config {}={} is not an int; using {}", key, raw, fallback);
            return fallback;
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        String raw = get(key, Boolean.toString(fallback));
        if (raw == null) {
            return fallback;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("true") || normalized.equals("1") || normalized.equals("yes")) {
            return true;
        }
        if (normalized.equals("false") || normalized.equals("0") || normalized.equals("no")) {
            return false;
        }
        return fallback;
    }

    public String userAgent() {
        return get(CRAWL_USER_AGENT, defaults.getUserAgent());
    }

    public int lookbackHours() {
        return Math.max(1, getInt(CRAWL_LOOKBACK_HOURS, defaults.getLookbackHours()));
    }

    public int maxConcurrency() {
        return Math.max(1, getInt(CRAWL_MAX_CONCURRENCY, defaults.getMaxConcurrency()));
    }

    public int maxConcurrentTasks() {
        return Math.max(1, getInt(CRAWL_MAX_CONCURRENT_TASKS, defaults.getMaxConcurrentTasks()));
    }

    public int perHostConcurrency() {
        return Math.max(1, getInt(CRAWL_PER_HOST_CONCURRENCY, defaults.getPerHostConcurrency()));
    }

    public int timeoutMs() {
        return Math.max(1000, getInt(CRAWL_TIMEOUT_MS, defaults.getTimeoutMs()));
    }

    public int maxBytes() {
        return Math.max(16_384, getInt(CRAWL_MAX_BYTES, defaults.getMaxBytes()));
    }

    public int maxRetries() {
        return Math.max(0, getInt(CRAWL_MAX_RETRIES, defaults.getMaxRetries()));
    }

    public String extractModel() {
        return get(EXTRACT_MODEL, defaults.getExtractModel());
    }

    public int extractMaxPostsPerJob() {
        return Math.max(0, getInt(EXTRACT_MAX_POSTS, defaults.getExtractMaxPostsPerJob()));
    }

    public int extractMaxPostsPerSource() {
        return Math.max(1, getInt(EXTRACT_MAX_POSTS_PER_SOURCE, defaults.getExtractMaxPostsPerSource()));
    }

    public int extractMaxQuestionsPerPost() {
        return Math.max(0, getInt(EXTRACT_MAX_QUESTIONS_PER_POST, defaults.getExtractMaxQuestionsPerPost()));
    }

    public int extractMaxTokens() {
        return Math.max(64, getInt(EXTRACT_MAX_TOKENS, defaults.getExtractMaxTokens()));
    }

    public CrawlerProperties defaults() {
        return defaults;
    }

        public Map<String, String> asMap() {
        Map<String, String> out = new LinkedHashMap<>();
        for (CrawlerConfig row : repository.findAll()) {
            out.put(row.getKey(), row.getValue());
        }
        return out;
    }
}

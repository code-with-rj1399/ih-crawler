package ai.interviewhq.crawler.domain;


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
@Table(name = "crawl_sources")
public class CrawlSource {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(nullable = false, unique = true)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String url;

    @Column(name = "source_kind", nullable = false)
    private String sourceKind;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "rate_limit_rpm", nullable = false)
    private int rateLimitRpm = 8;

    @Column(name = "crawl_delay_ms", nullable = false)
    private int crawlDelayMs = 1500;

    @Column(name = "per_host_concurrency", nullable = false)
    private int perHostConcurrency = 1;

    @Column(name = "robots_mode", nullable = false)
    private String robotsMode = "honor";
    @Column(name = "parser_config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> parserConfig = new LinkedHashMap<>();

    @Column(name = "last_crawled_at")
    private Instant lastCrawledAt;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures = 0;

    @Column(name = "circuit_open_until")
    private Instant circuitOpenUntil;

    private String notes;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (parserConfig == null) {
            parserConfig = new LinkedHashMap<>();
        }
        if (robotsMode == null) {
            robotsMode = "honor";
        }
    }
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isCircuitOpen() {
        return circuitOpenUntil != null && circuitOpenUntil.isAfter(Instant.now());
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSourceKind() {
        return sourceKind;
    }

    public void setSourceKind(String sourceKind) {
        this.sourceKind = sourceKind;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRateLimitRpm() {
        return rateLimitRpm;
    }

    public void setRateLimitRpm(int rateLimitRpm) {
        this.rateLimitRpm = rateLimitRpm;
    }

    public int getCrawlDelayMs() {
        return crawlDelayMs;
    }

    public void setCrawlDelayMs(int crawlDelayMs) {
        this.crawlDelayMs = crawlDelayMs;
    }

    public int getPerHostConcurrency() {
        return perHostConcurrency;
    }

    public void setPerHostConcurrency(int perHostConcurrency) {
        this.perHostConcurrency = perHostConcurrency;
    }

    public String getRobotsMode() {
        return robotsMode;
    }

    public void setRobotsMode(String robotsMode) {
        this.robotsMode = robotsMode;
    }

    public Map<String, Object> getParserConfig() {
        return parserConfig;
    }

    public void setParserConfig(Map<String, Object> parserConfig) {
        this.parserConfig = parserConfig;
    }

    public Instant getLastCrawledAt() {
        return lastCrawledAt;
    }

    public void setLastCrawledAt(Instant lastCrawledAt) {
        this.lastCrawledAt = lastCrawledAt;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public void setLastSuccessAt(Instant lastSuccessAt) {
        this.lastSuccessAt = lastSuccessAt;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public void setLastHttpStatus(Integer lastHttpStatus) {
        this.lastHttpStatus = lastHttpStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public void setLastError(String lastError) {
        this.lastError = lastError;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public void setConsecutiveFailures(int consecutiveFailures) {
        this.consecutiveFailures = consecutiveFailures;
    }

    public Instant getCircuitOpenUntil() {
        return circuitOpenUntil;
    }

    public void setCircuitOpenUntil(Instant circuitOpenUntil) {
        this.circuitOpenUntil = circuitOpenUntil;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

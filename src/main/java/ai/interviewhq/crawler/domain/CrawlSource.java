package ai.interviewhq.crawler.domain;


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
public class CrawlSource {
    private Integer id;

    private String slug;

    private String name;

    private String url;

    private String sourceKind;

    private boolean enabled = true;

    private int rateLimitRpm = 8;

    private int crawlDelayMs = 1500;

    private int perHostConcurrency = 1;

    private String robotsMode = "honor";
    private Map<String, Object> parserConfig = new LinkedHashMap<>();

    private Instant lastCrawledAt;

    private Instant lastSuccessAt;

    private Integer lastHttpStatus;

    private String lastError;

    private int consecutiveFailures = 0;

    private Instant circuitOpenUntil;

    private String notes;

    private Instant createdAt;

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

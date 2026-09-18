package ai.interviewhq.crawler.domain;


import java.time.Instant;
@Table(name = "crawler_config")
public class CrawlerConfig {
    @Column(name = "key")
    private String key;

    @Column(nullable = false)
    private String value;

    private String description;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CrawlerConfig() {
    }

    public CrawlerConfig(String key, String value, String description) {
        this.key = key;
        this.value = value;
        this.description = description;
    }
    void touch() {
        updatedAt = Instant.now();
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

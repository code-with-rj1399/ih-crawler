package ai.interviewhq.crawler.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "crawler_config")
public class CrawlerConfig {

    @Id
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

    @PrePersist
    @PreUpdate
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

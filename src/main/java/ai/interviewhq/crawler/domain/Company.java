package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
@JsonIgnoreProperties(ignoreUnknown = true)
public class Company {
    private Integer id;

    private String name;

    private String slug;
    private List<String> aliases = new ArrayList<>();

    private Instant createdAt;
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (aliases == null) {
            aliases = new ArrayList<>();
        }
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public void setAliases(List<String> aliases) {
        this.aliases = aliases;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

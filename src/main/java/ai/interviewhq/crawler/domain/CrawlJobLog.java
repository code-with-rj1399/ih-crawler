package ai.interviewhq.crawler.domain;


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
public class CrawlJobLog {
    private Integer id;

    private Integer jobId;

    private Integer sourceId;

    private String level = "info";

    private String eventCode = "note";

    private String message;
    private Map<String, Object> meta = new LinkedHashMap<>();

    private Instant createdAt;
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (meta == null) {
            meta = new LinkedHashMap<>();
        }
        if (level == null) {
            level = "info";
        }
        if (eventCode == null) {
            eventCode = "note";
        }
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getJobId() {
        return jobId;
    }

    public void setJobId(Integer jobId) {
        this.jobId = jobId;
    }

    public Integer getSourceId() {
        return sourceId;
    }

    public void setSourceId(Integer sourceId) {
        this.sourceId = sourceId;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getEventCode() {
        return eventCode;
    }

    public void setEventCode(String eventCode) {
        this.eventCode = eventCode;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Map<String, Object> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, Object> meta) {
        this.meta = meta;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

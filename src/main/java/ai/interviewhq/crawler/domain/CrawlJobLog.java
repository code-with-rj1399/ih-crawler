package ai.interviewhq.crawler.domain;


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
@Table(name = "crawl_job_logs")
public class CrawlJobLog {
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "job_id", nullable = false)
    private Integer jobId;

    @Column(name = "source_id")
    private Integer sourceId;

    @Column(nullable = false)
    private String level = "info";

    @Column(name = "event_code", nullable = false)
    private String eventCode = "note";

    @Column(nullable = false)
    private String message;
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> meta = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false, updatable = false)
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

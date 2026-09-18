package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "interview_posts", uniqueConstraints = @UniqueConstraint(columnNames = {"source_id", "url"}))
public class InterviewPost {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "source_id")
    private Integer sourceId;

    @Column(name = "page_id")
    private Integer pageId;

    @Column(name = "job_id")
    private Integer jobId;

    @Column(name = "external_id")
    private String externalId;

    @Column(nullable = false)
    private String url;

    private String title;

    private String author;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "raw_company")
    private String rawCompany;

    @Column(name = "raw_role")
    private String rawRole;

    @Column(name = "body_text")
    private String bodyText;

    @Column(name = "content_hash")
    private String contentHash;

    @Column(nullable = false)
    private boolean extracted;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "extraction_json", columnDefinition = "jsonb")
    private JsonNode extractionJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getSourceId() {
        return sourceId;
    }

    public void setSourceId(Integer sourceId) {
        this.sourceId = sourceId;
    }

    public Integer getPageId() {
        return pageId;
    }

    public void setPageId(Integer pageId) {
        this.pageId = pageId;
    }

    public Integer getJobId() {
        return jobId;
    }

    public void setJobId(Integer jobId) {
        this.jobId = jobId;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getAuthor() {
        return author;
    }

    public void setAuthor(String author) {
        this.author = author;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void setPostedAt(Instant postedAt) {
        this.postedAt = postedAt;
    }

    public String getRawCompany() {
        return rawCompany;
    }

    public void setRawCompany(String rawCompany) {
        this.rawCompany = rawCompany;
    }

    public String getRawRole() {
        return rawRole;
    }

    public void setRawRole(String rawRole) {
        this.rawRole = rawRole;
    }

    public String getBodyText() {
        return bodyText;
    }

    public void setBodyText(String bodyText) {
        this.bodyText = bodyText;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public boolean isExtracted() {
        return extracted;
    }

    public void setExtracted(boolean extracted) {
        this.extracted = extracted;
    }

    public JsonNode getExtractionJson() {
        return extractionJson;
    }

    public void setExtractionJson(JsonNode extractionJson) {
        this.extractionJson = extractionJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
public class InterviewPost {
    private Integer id;

    private Integer sourceId;

    private Integer pageId;

    private Integer jobId;

    private String externalId;

    private String url;

    private String title;

    private String author;

    private Instant postedAt;

    private String rawCompany;

    private String rawRole;

    private String bodyText;

    private String contentHash;

    private boolean extracted;
    private JsonNode extractionJson;

    private Instant createdAt;
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

package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


import java.time.Instant;
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrawlPage {
    private Integer id;

    private Integer sourceId;

    private Integer jobId;

    private String url;

    private String canonicalUrl;

    private Integer httpStatus;

    private String contentType;

    private String contentHash;

    private String bodyExcerpt;

    private String etag;

    private Boolean robotsAllowed;

    private Instant publishedAt;

    private Instant fetchedAt;

    private String error;
    void onCreate() {
        if (fetchedAt == null) {
            fetchedAt = Instant.now();
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

    public Integer getJobId() {
        return jobId;
    }

    public void setJobId(Integer jobId) {
        this.jobId = jobId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getCanonicalUrl() {
        return canonicalUrl;
    }

    public void setCanonicalUrl(String canonicalUrl) {
        this.canonicalUrl = canonicalUrl;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public String getBodyExcerpt() {
        return bodyExcerpt;
    }

    public void setBodyExcerpt(String bodyExcerpt) {
        this.bodyExcerpt = bodyExcerpt;
    }

    public String getEtag() {
        return etag;
    }

    public void setEtag(String etag) {
        this.etag = etag;
    }

    public Boolean getRobotsAllowed() {
        return robotsAllowed;
    }

    public void setRobotsAllowed(Boolean robotsAllowed) {
        this.robotsAllowed = robotsAllowed;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}

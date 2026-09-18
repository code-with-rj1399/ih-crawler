package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;


import java.time.Instant;
@JsonIgnoreProperties(ignoreUnknown = true)
public class CrawlJob {

    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String SUCCEEDED = "succeeded";
    public static final String FAILED = "failed";
    public static final String PARTIAL = "partial";
    private Integer id;

    private String status = QUEUED;

    private String trigger = "manual";

    private int lookbackHours = 24;

    private Instant startedAt;

    private Instant finishedAt;

    private int sourcesPlanned;

    private int sourcesOk;

    private int sourcesFailed;

    private int pagesFetched;

    private int pagesSkipped;

    private int postsDiscovered;

    private int postsExtracted;

    private int questionsUpserted;

    private int http429Count;

    private int blockedCount;

    private String errorSummary;

    private Instant createdAt;
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = QUEUED;
        }
        if (trigger == null) {
            trigger = "manual";
        }
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getTrigger() {
        return trigger;
    }

    public void setTrigger(String trigger) {
        this.trigger = trigger;
    }

    public int getLookbackHours() {
        return lookbackHours;
    }

    public void setLookbackHours(int lookbackHours) {
        this.lookbackHours = lookbackHours;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public int getSourcesPlanned() {
        return sourcesPlanned;
    }

    public void setSourcesPlanned(int sourcesPlanned) {
        this.sourcesPlanned = sourcesPlanned;
    }

    public int getSourcesOk() {
        return sourcesOk;
    }

    public void setSourcesOk(int sourcesOk) {
        this.sourcesOk = sourcesOk;
    }

    public int getSourcesFailed() {
        return sourcesFailed;
    }

    public void setSourcesFailed(int sourcesFailed) {
        this.sourcesFailed = sourcesFailed;
    }

    public int getPagesFetched() {
        return pagesFetched;
    }

    public void setPagesFetched(int pagesFetched) {
        this.pagesFetched = pagesFetched;
    }

    public int getPagesSkipped() {
        return pagesSkipped;
    }

    public void setPagesSkipped(int pagesSkipped) {
        this.pagesSkipped = pagesSkipped;
    }

    public int getPostsDiscovered() {
        return postsDiscovered;
    }

    public void setPostsDiscovered(int postsDiscovered) {
        this.postsDiscovered = postsDiscovered;
    }

    public int getPostsExtracted() {
        return postsExtracted;
    }

    public void setPostsExtracted(int postsExtracted) {
        this.postsExtracted = postsExtracted;
    }

    public int getQuestionsUpserted() {
        return questionsUpserted;
    }

    public void setQuestionsUpserted(int questionsUpserted) {
        this.questionsUpserted = questionsUpserted;
    }

    public int getHttp429Count() {
        return http429Count;
    }

    public void setHttp429Count(int http429Count) {
        this.http429Count = http429Count;
    }

    public int getBlockedCount() {
        return blockedCount;
    }

    public void setBlockedCount(int blockedCount) {
        this.blockedCount = blockedCount;
    }

    public String getErrorSummary() {
        return errorSummary;
    }

    public void setErrorSummary(String errorSummary) {
        this.errorSummary = errorSummary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

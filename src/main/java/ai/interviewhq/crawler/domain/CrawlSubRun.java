package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class CrawlSubRun {
    private Integer id;
    private Integer jobId;
    private Integer sourceId;
    private String sourceSlug;
    private String status = "running";
    private Instant startedAt;
    private Instant finishedAt;
    private int pagesFetched;
    private int pagesSkipped;
    private int postsDiscovered;
    private int postsExtracted;
    private int questionsUpserted;
    private int modelCalls;
    private int http429Count;
    private int blockedCount;
    private String errorSummary;
    private Instant createdAt;

    public Integer getId(){return id;} public void setId(Integer v){id=v;}
    public Integer getJobId(){return jobId;} public void setJobId(Integer v){jobId=v;}
    public Integer getSourceId(){return sourceId;} public void setSourceId(Integer v){sourceId=v;}
    public String getSourceSlug(){return sourceSlug;} public void setSourceSlug(String v){sourceSlug=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public Instant getStartedAt(){return startedAt;} public void setStartedAt(Instant v){startedAt=v;}
    public Instant getFinishedAt(){return finishedAt;} public void setFinishedAt(Instant v){finishedAt=v;}
    public int getPagesFetched(){return pagesFetched;} public void setPagesFetched(int v){pagesFetched=v;}
    public int getPagesSkipped(){return pagesSkipped;} public void setPagesSkipped(int v){pagesSkipped=v;}
    public int getPostsDiscovered(){return postsDiscovered;} public void setPostsDiscovered(int v){postsDiscovered=v;}
    public int getPostsExtracted(){return postsExtracted;} public void setPostsExtracted(int v){postsExtracted=v;}
    public int getQuestionsUpserted(){return questionsUpserted;} public void setQuestionsUpserted(int v){questionsUpserted=v;}
    public int getModelCalls(){return modelCalls;} public void setModelCalls(int v){modelCalls=v;}
    public int getHttp429Count(){return http429Count;} public void setHttp429Count(int v){http429Count=v;}
    public int getBlockedCount(){return blockedCount;} public void setBlockedCount(int v){blockedCount=v;}
    public String getErrorSummary(){return errorSummary;} public void setErrorSummary(String v){errorSummary=v;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant v){createdAt=v;}
}

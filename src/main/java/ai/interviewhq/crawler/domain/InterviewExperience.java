package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InterviewExperience {
    private Integer id;
    private Integer sourceId;
    private String sourcePlatform;
    private String title;
    private String summary;
    private String author;
    private Instant postedAt;
    private String originalPostUrl;
    private String company;
    private String role;
    private String level;
    private String location;
    private Float candidateYoE;
    private Integer questionCount;
    private String dedupeHash;
    private Instant createdAt;

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getSourceId() { return sourceId; }
    public void setSourceId(Integer sourceId) { this.sourceId = sourceId; }
    public String getSourcePlatform() { return sourcePlatform; }
    public void setSourcePlatform(String sourcePlatform) { this.sourcePlatform = sourcePlatform; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }
    public String getOriginalPostUrl() { return originalPostUrl; }
    public void setOriginalPostUrl(String originalPostUrl) { this.originalPostUrl = originalPostUrl; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getLevel() { return level; }
    public void setLevel(String level) { this.level = level; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Float getCandidateYoE() { return candidateYoE; }
    public void setCandidateYoE(Float candidateYoE) { this.candidateYoE = candidateYoE; }
    public Integer getQuestionCount() { return questionCount; }
    public void setQuestionCount(Integer questionCount) { this.questionCount = questionCount; }
    public String getDedupeHash() { return dedupeHash; }
    public void setDedupeHash(String dedupeHash) { this.dedupeHash = dedupeHash; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

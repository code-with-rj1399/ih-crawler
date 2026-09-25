package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InterviewQuestion {
    private Integer id;
    private Integer experienceId;
    private String problemUrl;

    private List<String> questionTypes = new ArrayList<>();
    private String difficulty;
    private String questionText;
    private String questionDescription;
    private String candidateApproach;
    private Float confidence;
    private Float questionGranularity;

    private String modelName;
    private String dedupeHash;
    private Instant extractedAt;
    private Instant createdAt;

    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (extractedAt == null) extractedAt = now;
        if (questionTypes == null) questionTypes = new ArrayList<>();
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }
    public Integer getExperienceId() { return experienceId; }
    public void setExperienceId(Integer experienceId) { this.experienceId = experienceId; }
    public String getProblemUrl() { return problemUrl; }
    public void setProblemUrl(String problemUrl) { this.problemUrl = problemUrl; }
    public List<String> getQuestionTypes() { return questionTypes; }
    public void setQuestionTypes(List<String> questionTypes) { this.questionTypes = questionTypes; }
    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }
    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }
    public String getQuestionDescription() { return questionDescription; }
    public void setQuestionDescription(String questionDescription) { this.questionDescription = questionDescription; }
    public String getCandidateApproach() { return candidateApproach; }
    public void setCandidateApproach(String candidateApproach) { this.candidateApproach = candidateApproach; }
    public Float getConfidence() { return confidence; }
    public void setConfidence(Float confidence) { this.confidence = confidence; }
    public Float getQuestionGranularity() { return questionGranularity; }
    public void setQuestionGranularity(Float questionGranularity) { this.questionGranularity = questionGranularity; }
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    public String getDedupeHash() { return dedupeHash; }
    public void setDedupeHash(String dedupeHash) { this.dedupeHash = dedupeHash; }
    public Instant getExtractedAt() { return extractedAt; }
    public void setExtractedAt(Instant extractedAt) { this.extractedAt = extractedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

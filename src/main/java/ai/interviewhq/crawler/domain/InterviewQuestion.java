package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InterviewQuestion {
    private Integer id;
    private Integer postId;

    private String sourcePlatform;
    private String originalPostUrl;
    private String problemUrl;
    private LocalDate postDate;

    private String company;
    private String role;
    private String level;
    private String location;
    private Float candidateYoE;
    private String outcome;

    private String roundType;
    private String questionType;
    private String difficulty;
    private List<String> topics = new ArrayList<>();

    private String questionText;
    private String questionDescription;
    private String candidateApproach;
    private Float confidence;

    private String modelName;
    private String dedupeHash;
    private Instant extractedAt;
    private Instant createdAt;

    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (extractedAt == null) extractedAt = now;
        if (topics == null) topics = new ArrayList<>();
    }

    public Integer getId() { return id; }
    public void setId(Integer id) { this.id = id; }

    public Integer getPostId() { return postId; }
    public void setPostId(Integer postId) { this.postId = postId; }

    public String getSourcePlatform() { return sourcePlatform; }
    public void setSourcePlatform(String sourcePlatform) { this.sourcePlatform = sourcePlatform; }

    public String getOriginalPostUrl() { return originalPostUrl; }
    public void setOriginalPostUrl(String originalPostUrl) { this.originalPostUrl = originalPostUrl; }

    public String getProblemUrl() { return problemUrl; }
    public void setProblemUrl(String problemUrl) { this.problemUrl = problemUrl; }

    public LocalDate getPostDate() { return postDate; }
    public void setPostDate(LocalDate postDate) { this.postDate = postDate; }

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

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getRoundType() { return roundType; }
    public void setRoundType(String roundType) { this.roundType = roundType; }

    public String getQuestionType() { return questionType; }
    public void setQuestionType(String questionType) { this.questionType = questionType; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public List<String> getTopics() { return topics; }
    public void setTopics(List<String> topics) { this.topics = topics; }

    public String getQuestionText() { return questionText; }
    public void setQuestionText(String questionText) { this.questionText = questionText; }

    public String getQuestionDescription() { return questionDescription; }
    public void setQuestionDescription(String questionDescription) { this.questionDescription = questionDescription; }

    public String getCandidateApproach() { return candidateApproach; }
    public void setCandidateApproach(String candidateApproach) { this.candidateApproach = candidateApproach; }

    public Float getConfidence() { return confidence; }
    public void setConfidence(Float confidence) { this.confidence = confidence; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public String getDedupeHash() { return dedupeHash; }
    public void setDedupeHash(String dedupeHash) { this.dedupeHash = dedupeHash; }

    public Instant getExtractedAt() { return extractedAt; }
    public void setExtractedAt(Instant extractedAt) { this.extractedAt = extractedAt; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

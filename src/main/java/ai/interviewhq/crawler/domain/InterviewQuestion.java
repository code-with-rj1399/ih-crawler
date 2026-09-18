package ai.interviewhq.crawler.domain;


import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
public class InterviewQuestion {
    private Integer id;

    private Integer postId;

    private String company;

    private String role;

    private String level;

    private String roundType;

    private String questionType;

    private String questionText;

    private LocalDate askedAt;

    private String location;

    private String posterName;

    private String difficulty;
    private List<String> topics = new ArrayList<>();

    private Float confidence;

    private String modelName;

    private String dedupeHash;

    private Instant extractedAt;

    private Instant createdAt;
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (extractedAt == null) {
            extractedAt = now;
        }
        if (topics == null) {
            topics = new ArrayList<>();
        }
    }

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getPostId() {
        return postId;
    }

    public void setPostId(Integer postId) {
        this.postId = postId;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getRoundType() {
        return roundType;
    }

    public void setRoundType(String roundType) {
        this.roundType = roundType;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public String getQuestionText() {
        return questionText;
    }

    public void setQuestionText(String questionText) {
        this.questionText = questionText;
    }

    public LocalDate getAskedAt() {
        return askedAt;
    }

    public void setAskedAt(LocalDate askedAt) {
        this.askedAt = askedAt;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getPosterName() {
        return posterName;
    }

    public void setPosterName(String posterName) {
        this.posterName = posterName;
    }

    public String getDifficulty() {
        return difficulty;
    }

    public void setDifficulty(String difficulty) {
        this.difficulty = difficulty;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    public Float getConfidence() {
        return confidence;
    }

    public void setConfidence(Float confidence) {
        this.confidence = confidence;
    }

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getDedupeHash() {
        return dedupeHash;
    }

    public void setDedupeHash(String dedupeHash) {
        this.dedupeHash = dedupeHash;
    }

    public Instant getExtractedAt() {
        return extractedAt;
    }

    public void setExtractedAt(Instant extractedAt) {
        this.extractedAt = extractedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

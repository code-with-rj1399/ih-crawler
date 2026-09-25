package ai.interviewhq.crawler.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class InterviewExperienceLookup {
    private Integer experienceId;
    private String dedupeHash;

    public InterviewExperienceLookup() {
    }

    public InterviewExperienceLookup(Integer experienceId, String dedupeHash) {
        this.experienceId = experienceId;
        this.dedupeHash = dedupeHash;
    }

    public Integer getExperienceId() { return experienceId; }
    public void setExperienceId(Integer experienceId) { this.experienceId = experienceId; }
    public String getDedupeHash() { return dedupeHash; }
    public void setDedupeHash(String dedupeHash) { this.dedupeHash = dedupeHash; }
}

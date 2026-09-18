package ai.interviewhq.crawler.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "crawler")
public class CrawlerProperties {

    private String userAgent = "InterviewHQBot/1.0 (ih-crawler; +https://interviewhq.ai/bot)";
    private int lookbackHours = 24;
    private int maxConcurrency = 4;
    private int perHostConcurrency = 1;
    private int timeoutMs = 15_000;
    private int maxBytes = 1_048_576;
    private int maxRetries = 3;
    private int circuitFailures = 5;
    private int circuitOpenMinutes = 30;
    private int robotsCacheHours = 6;
    private String extractModel = "grok-4.5";
    private int extractMaxPostsPerJob = 12;
    private int extractMaxPostsPerSource = 12;
    private int extractMaxQuestionsPerPost = 0;
    private int extractMaxTokens = 1200;
    private String xaiBaseUrl = "https://api.x.ai/v1";

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public int getLookbackHours() {
        return lookbackHours;
    }

    public void setLookbackHours(int lookbackHours) {
        this.lookbackHours = lookbackHours;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getPerHostConcurrency() {
        return perHostConcurrency;
    }

    public void setPerHostConcurrency(int perHostConcurrency) {
        this.perHostConcurrency = perHostConcurrency;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxBytes() {
        return maxBytes;
    }

    public void setMaxBytes(int maxBytes) {
        this.maxBytes = maxBytes;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public int getCircuitFailures() {
        return circuitFailures;
    }

    public void setCircuitFailures(int circuitFailures) {
        this.circuitFailures = circuitFailures;
    }

    public int getCircuitOpenMinutes() {
        return circuitOpenMinutes;
    }

    public void setCircuitOpenMinutes(int circuitOpenMinutes) {
        this.circuitOpenMinutes = circuitOpenMinutes;
    }

    public int getRobotsCacheHours() {
        return robotsCacheHours;
    }

    public void setRobotsCacheHours(int robotsCacheHours) {
        this.robotsCacheHours = robotsCacheHours;
    }

    public String getExtractModel() {
        return extractModel;
    }

    public void setExtractModel(String extractModel) {
        this.extractModel = extractModel;
    }

    public int getExtractMaxPostsPerJob() {
        return extractMaxPostsPerJob;
    }

    public void setExtractMaxPostsPerJob(int extractMaxPostsPerJob) {
        this.extractMaxPostsPerJob = extractMaxPostsPerJob;
    }

    public int getExtractMaxPostsPerSource() {
        return extractMaxPostsPerSource;
    }

    public void setExtractMaxPostsPerSource(int extractMaxPostsPerSource) {
        this.extractMaxPostsPerSource = extractMaxPostsPerSource;
    }

    public int getExtractMaxQuestionsPerPost() {
        return extractMaxQuestionsPerPost;
    }

    public void setExtractMaxQuestionsPerPost(int extractMaxQuestionsPerPost) {
        this.extractMaxQuestionsPerPost = extractMaxQuestionsPerPost;
    }

    public int getExtractMaxTokens() {
        return extractMaxTokens;
    }

    public void setExtractMaxTokens(int extractMaxTokens) {
        this.extractMaxTokens = extractMaxTokens;
    }

    public String getXaiBaseUrl() {
        return xaiBaseUrl;
    }

    public void setXaiBaseUrl(String xaiBaseUrl) {
        this.xaiBaseUrl = xaiBaseUrl;
    }
}

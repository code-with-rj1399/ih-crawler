package ai.interviewhq.crawler.crawl;

public class JobAlreadyRunningException extends RuntimeException {

    public JobAlreadyRunningException() {
        super("A crawl job is already running");
    }
}

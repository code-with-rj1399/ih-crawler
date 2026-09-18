package ai.interviewhq.crawler.crawl;

public class FetchBlockedException extends RuntimeException {

    private final int status;
    private final String url;

    public FetchBlockedException(int status, String url, String message) {
        super(message);
        this.status = status;
        this.url = url;
    }

    public int getStatus() {
        return status;
    }

    public String getUrl() {
        return url;
    }
}

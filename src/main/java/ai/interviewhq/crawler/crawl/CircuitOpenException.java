package ai.interviewhq.crawler.crawl;

import java.time.Instant;

public class CircuitOpenException extends RuntimeException {

    private final Instant until;

    public CircuitOpenException(String slug, Instant until) {
        super("Circuit open for source " + slug + " until " + until);
        this.until = until;
    }

    public Instant getUntil() {
        return until;
    }
}

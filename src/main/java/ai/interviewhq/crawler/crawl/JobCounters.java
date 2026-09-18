package ai.interviewhq.crawler.crawl;

import java.util.concurrent.atomic.AtomicInteger;

public final class JobCounters {
    public final AtomicInteger sourcesOk = new AtomicInteger();
    public final AtomicInteger sourcesFailed = new AtomicInteger();
    public final AtomicInteger pagesFetched = new AtomicInteger();
    public final AtomicInteger pagesSkipped = new AtomicInteger();
    public final AtomicInteger postsDiscovered = new AtomicInteger();
    public final AtomicInteger postsExtracted = new AtomicInteger();
    public final AtomicInteger questionsUpserted = new AtomicInteger();
    public final AtomicInteger http429 = new AtomicInteger();
    public final AtomicInteger blocked = new AtomicInteger();
}

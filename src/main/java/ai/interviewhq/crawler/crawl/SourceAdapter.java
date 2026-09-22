package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

public interface SourceAdapter {

    String kind();

    /**
     * Crawls a source and emits each completed page/post as soon as its
     * source-specific fetch/enrichment flow is complete.
     *
     * Implementations must invoke the consumer sequentially for a source.
     */
    default void crawlStreaming(
            CrawlSource source,
            Instant lookback,
            PoliteFetcher fetcher,
            Consumer<ParsedEntry> consumer) throws Exception {
        for (ParsedEntry entry : crawl(source, lookback, fetcher)) {
            consumer.accept(entry);
        }
    }

    /**
     * Legacy batch API retained for adapters and callers that still need it.
     */
    List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception;
}

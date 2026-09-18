package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;

import java.time.Instant;
import java.util.List;

public interface SourceAdapter {

    String kind();

    List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception;
}

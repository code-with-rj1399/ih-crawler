package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.crawl.ChromiumSiteCrawler;
import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.crawl.SourceAdapter;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * LeetCode Discuss source.
 *
 * LeetCode uses the same Chromium discovery/fetch pipeline as the other
 * browser-backed sources. The source-specific adapter only identifies the
 * source kind and delegates crawling to the shared ChromiumSiteCrawler.
 */
@Component
public class LeetcodeDiscussAdapter implements SourceAdapter {

    private final ChromiumSiteCrawler siteCrawler;
    private final int maxListingPages;
    private final int maxUrls;

    public LeetcodeDiscussAdapter(
            ChromiumSiteCrawler siteCrawler,
            @Value("${crawler.browser.max-listing-pages:3}") int maxListingPages,
            @Value("${crawler.extract-max-posts-per-source:5}") int maxUrls) {
        this.siteCrawler = siteCrawler;
        this.maxListingPages = Math.max(1, maxListingPages);
        this.maxUrls = Math.max(1, maxUrls);
    }

    @Override
    public String kind() {
        return "leetcode_discuss";
    }

    @Override
    public List<ParsedEntry> crawl(
            CrawlSource source,
            Instant lookback,
            PoliteFetcher fetcher) {

        return siteCrawler.crawl(
                source,
                lookback,
                maxUrls,
                maxListingPages
        );
    }
}

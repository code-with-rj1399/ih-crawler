package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.ChromiumSiteCrawler;
import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.crawl.SourceAdapter;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class BlindAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(BlindAdapter.class);

    private final ChromiumSiteCrawler siteCrawler;
    private final CrawlerSettings settings;
    private final int maxListingPages;

    public BlindAdapter(
            ChromiumSiteCrawler siteCrawler,
            CrawlerSettings settings,
            @Value("${crawler.browser.max-listing-pages:3}") int maxListingPages) {
        this.siteCrawler = siteCrawler;
        this.settings = settings;
        this.maxListingPages = Math.max(1, maxListingPages);
    }

    @Override
    public String kind() {
        return "blind";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) {
        log.info("Blind crawl via Chromium (no AI search): {}", source.getUrl());
        return siteCrawler.crawl(source, lookback, Math.max(1, settings.extractMaxPostsPerSource()), maxListingPages);
    }
}

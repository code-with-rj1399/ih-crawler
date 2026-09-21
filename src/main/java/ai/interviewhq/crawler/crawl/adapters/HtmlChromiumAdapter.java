package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.ChromiumSiteCrawler;
import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.crawl.SourceAdapter;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
public class HtmlChromiumAdapter implements SourceAdapter {

    private final ChromiumSiteCrawler siteCrawler;
    private final CrawlerSettings settings;
    private final int maxListingPages;

    public HtmlChromiumAdapter(
            ChromiumSiteCrawler siteCrawler,
            CrawlerSettings settings,
            @Value("${crawler.browser.max-listing-pages:3}") int maxListingPages) {
        this.siteCrawler = siteCrawler;
        this.settings = settings;
        this.maxListingPages = Math.max(1, maxListingPages);
    }

    @Override
    public String kind() {
        return "html";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) {
        int maxUrls = Math.max(1, settings.extractMaxPostsPerSource());
        return siteCrawler.crawl(source, lookback, maxUrls, maxListingPages);
    }
}

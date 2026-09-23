package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.browser.ChromiumBrowserClient;
import ai.interviewhq.crawler.crawl.adapters.ParserConfigs;
import ai.interviewhq.crawler.crawl.discovery.InterviewLinkDiscoverer;
import ai.interviewhq.crawler.crawl.discovery.PageContentExtractor;
import ai.interviewhq.crawler.crawl.http.FetchMode;
import ai.interviewhq.crawler.crawl.http.HybridPageFetcher;
import ai.interviewhq.crawler.crawl.http.PageSnapshot;
import ai.interviewhq.crawler.crawl.leetcode.LeetcodeGraphqlClient;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * For one site: discover interview article URLs from listing pages, then fetch
 * each article. HTTP is tried first; Chromium is the fallback for JS shells.
 *
 * LeetCode is intentionally handled through its GraphQL API rather than HTML
 * discovery because Discuss is a JS application and the public GraphQL
 * operation provides structured post metadata and pagination.
 */
@Component
public class ChromiumSiteCrawler {

    private static final Logger log = LoggerFactory.getLogger(ChromiumSiteCrawler.class);

    private final HybridPageFetcher hybrid;
    private final ChromiumBrowserClient browser;
    private final LeetcodeGraphqlClient leetcodeGraphql;
    private final InterviewLinkDiscoverer discoverer = new InterviewLinkDiscoverer();
    private final PageContentExtractor contentExtractor = new PageContentExtractor();

    public ChromiumSiteCrawler(
            HybridPageFetcher hybrid,
            ChromiumBrowserClient browser,
            LeetcodeGraphqlClient leetcodeGraphql) {
        this.hybrid = hybrid;
        this.browser = browser;
        this.leetcodeGraphql = leetcodeGraphql;
    }

    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, int maxUrls, int maxListingPages) {
        List<ParsedEntry> entries = new ArrayList<>();
        crawlStreaming(source, lookback, maxUrls, maxListingPages, entries::add);
        return entries;
    }

    /**
     * Crawls one source sequentially. Each discovered article is fully fetched
     * and parsed, then emitted to the caller before the next article starts.
     */
    public void crawlStreaming(
            CrawlSource source,
            Instant lookback,
            int maxUrls,
            int maxListingPages,
            Consumer<ParsedEntry> consumer) {
        if (source == null || source.getUrl() == null || source.getUrl().isBlank()) {
            return;
        }

        int urlCap = Math.max(1, maxUrls);

        // LeetCode-only path: do not render the Discuss listing and do not
        // discover links from the SPA DOM. Use the documented/current GraphQL
        // operation used by the LeetCode interview ingestion implementation.
        if ("leetcode-interviews".equals(source.getSlug())) {
            log.info("Using LeetCode GraphQL discovery: source={} cap={} cutoff={}",
                    source.getSlug(), urlCap, lookback);
            leetcodeGraphql.fetchRecentStreaming(source, lookback, urlCap, consumer);
            return;
        }

        int listingCap = Math.max(1, maxListingPages);
        FetchMode mode = FetchMode.from(source);
        boolean browserListing = ParserConfigs.bool(source, "browserListing", mode.preferBrowser());
        int listingScrolls = browserListing ? 2 : 0;

        Set<String> listingSeen = new LinkedHashSet<>();
        Set<String> articleUrls = new LinkedHashSet<>();
        Deque<String> listings = new ArrayDeque<>();
        listings.add(source.getUrl());

        String listingReferer = source.getUrl();
        int listingCount = 0;

        try (ChromiumBrowserClient.Session session = browser.openSession()) {
            while (!listings.isEmpty() && listingCount < listingCap) {
                String listingUrl = listings.poll();
                if (listingUrl == null || !listingSeen.add(listingUrl)) {
                    continue;
                }
                listingCount++;

                FetchMode listingMode = browserListing ? FetchMode.BROWSER_FIRST : mode;
                PageSnapshot listing = hybrid.fetch(
                        source,
                        listingUrl,
                        listingCount == 1 ? null : source.getUrl(),
                        listingMode,
                        listingScrolls,
                        session
                );
                if (!listing.isSuccess()) {
                    log.warn("Listing failed: source={} url={} status={} challenge={} browser={}",
                            source.getSlug(), listingUrl, listing.status(), listing.challenge(), listing.usedBrowser());
                    continue;
                }

                listingReferer = listing.finalUrl();
                List<InterviewLinkDiscoverer.DiscoveredLink> links =
                        discoverer.discover(listing.finalUrl(), listing.html(), urlCap * 4);
                for (InterviewLinkDiscoverer.DiscoveredLink link : links) {
                    articleUrls.add(link.url());
                    log.info("Discovered interview candidate: source={} listingUrl={} candidateUrl={} score={} anchor={}",
                            source.getSlug(), listing.finalUrl(), link.url(), link.score(), link.anchorText());
                }
                log.info("Listing parsed: source={} url={} interviewLinks={} totalUnique={} via={}",
                        source.getSlug(), listing.finalUrl(), links.size(), articleUrls.size(),
                        listing.usedBrowser() ? "chromium" : "http");
                log.debug("Listing text source={} url={} chars={}",
                        source.getSlug(), listing.finalUrl(),
                        listing.text() == null ? 0 : listing.text().length());

                for (String next : discoverer.paginationUrls(listing.finalUrl(), listing.html())) {
                    if (!listingSeen.contains(next)) {
                        listings.add(next);
                    }
                }
            }

            int emitted = 0;
            int fetched = 0;
            int httpHits = 0;
            int browserHits = 0;
            for (String articleUrl : articleUrls) {
                if (emitted >= urlCap) {
                    break;
                }
                fetched++;
                try {
                    PageSnapshot page = hybrid.fetch(
                            source, articleUrl, listingReferer, FetchMode.HTTP_FIRST, 0, session);
                    if (!page.isSuccess()) {
                        log.info("Skipping article: source={} url={} status={} challenge={} via={}",
                                source.getSlug(), articleUrl, page.status(), page.challenge(),
                                page.usedBrowser() ? "chromium" : "http");
                        continue;
                    }
                    if (page.usedBrowser()) {
                        browserHits++;
                    } else {
                        httpHits++;
                    }
                    log.debug("Article fetched: source={} url={} status={} via={} text={}",
                            source.getSlug(), page.finalUrl(), page.status(),
                            page.usedBrowser() ? "chromium" : "http",
                            page.text() == null ? 0 : page.text().length());

                    ParsedEntry entry = toEntry(page, lookback, source.getSlug());
                    if (entry != null) {
                        consumer.accept(entry);
                        emitted++;
                    }
                } catch (RuntimeException ex) {
                    log.warn("Article fetch failed: source={} url={}: {}",
                            source.getSlug(), articleUrl, ex.getMessage());
                }
            }

            log.info("Site crawl finished: source={} listings={} discovered={} fetched={} kept={} httpHits={} browserHits={}",
                    source.getSlug(), listingCount, articleUrls.size(), fetched, emitted, httpHits, browserHits);
        }
    }

    private ParsedEntry toEntry(PageSnapshot page, Instant lookback, String currentSourceSlug) {
        PageContentExtractor.ExtractedPage extracted =
                contentExtractor.extract(page.finalUrl(), page.html(), page.text());
        if (extracted.body() == null || extracted.body().isBlank()) {
            return null;
        }
        Instant published = extracted.publishedAt();
        if (published != null && lookback != null && published.isBefore(lookback)) {
            log.info("Rejecting article due to freshness: source={} url={} publishedAt={} cutoff={}",
                    currentSourceSlug, page.finalUrl(), published, lookback);
            return null;
        }

        return ParsedEntry.builder(page.requestedUrl())
                .canonicalUrl(page.finalUrl())
                .title(extracted.title() == null ? page.title() : extracted.title())
                .author(extracted.author())
                .publishedAt(published)
                .bodyText(extracted.body())
                .contentType(page.contentType() == null ? "text/html" : page.contentType())
                .httpStatus(page.status())
                .etag(page.etag())
                .contentHash(Hashing.sha256Hex(extracted.body()))
                .robotsAllowed(page.robotsAllowed())
                .build();
    }
}

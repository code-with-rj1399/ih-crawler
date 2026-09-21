package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.browser.ChromiumBrowserClient;
import ai.interviewhq.crawler.crawl.discovery.InterviewLinkDiscoverer;
import ai.interviewhq.crawler.crawl.discovery.PageContentExtractor;
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

/**
 * For one site: Chromium-open the listing, collect interview article URLs,
 * then fetch each article one-by-one. The model is never asked to browse.
 */
@Component
public class ChromiumSiteCrawler {

    private static final Logger log = LoggerFactory.getLogger(ChromiumSiteCrawler.class);

    private final ChromiumBrowserClient browser;
    private final InterviewLinkDiscoverer discoverer = new InterviewLinkDiscoverer();
    private final PageContentExtractor contentExtractor = new PageContentExtractor();

    public ChromiumSiteCrawler(ChromiumBrowserClient browser) {
        this.browser = browser;
    }

    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, int maxUrls, int maxListingPages) {
        if (source == null || source.getUrl() == null || source.getUrl().isBlank()) {
            return List.of();
        }

        int urlCap = Math.max(1, maxUrls);
        int listingCap = Math.max(1, maxListingPages);

        Set<String> listingSeen = new LinkedHashSet<>();
        Set<String> articleUrls = new LinkedHashSet<>();
        Deque<String> listings = new ArrayDeque<>();
        listings.add(source.getUrl());

        String listingReferer = source.getUrl();
        int listingCount = 0;

        while (!listings.isEmpty() && listingCount < listingCap) {
            String listingUrl = listings.poll();
            if (listingUrl == null || !listingSeen.add(listingUrl)) {
                continue;
            }
            listingCount++;

            ChromiumBrowserClient.BrowserPage listing = browser.fetch(
                    source, listingUrl, listingCount == 1 ? null : source.getUrl());
            if (!listing.isSuccess()) {
                log.warn("Chromium listing failed: source={} url={} status={} challenge={}",
                        source.getSlug(), listingUrl, listing.status(), listing.challenge());
                continue;
            }

            listingReferer = listing.url();
            List<InterviewLinkDiscoverer.DiscoveredLink> links =
                    discoverer.discover(listing.url(), listing.html(), urlCap * 4);
            for (InterviewLinkDiscoverer.DiscoveredLink link : links) {
                articleUrls.add(link.url());
            }
            log.info("Chromium listing parsed: source={} url={} interviewLinks={} totalUnique={}",
                    source.getSlug(), listing.url(), links.size(), articleUrls.size());

            log.info("""
                    ==================== CHROMIUM LISTING PAGE CONTENT ====================
                    source={}
                    url={}
                    title={}
                    status={}
                    challenge={}
                    htmlLength={}
                    textLength={}
                    PAGE TEXT:
                    ---
                    {}
                    ---
                    ================== END CHROMIUM LISTING PAGE CONTENT ==================
                    """,
                    source.getSlug(),
                    listing.url(),
                    listing.title(),
                    listing.status(),
                    listing.challenge(),
                    listing.html() == null ? 0 : listing.html().length(),
                    listing.text() == null ? 0 : listing.text().length(),
                    listing.text() == null ? "" : listing.text());

            for (String next : discoverer.paginationUrls(listing.url(), listing.html())) {
                if (!listingSeen.contains(next)) {
                    listings.add(next);
                }
            }
        }

        List<ParsedEntry> entries = new ArrayList<>();
        int fetched = 0;
        for (String articleUrl : articleUrls) {
            if (entries.size() >= urlCap) {
                break;
            }
            fetched++;
            try {
                ChromiumBrowserClient.BrowserPage page = browser.fetch(source, articleUrl, listingReferer);
                if (!page.isSuccess()) {
                    log.info("Skipping article: source={} url={} status={} challenge={}",
                            source.getSlug(), articleUrl, page.status(), page.challenge());
                    continue;
                }
                log.info("""
                        ==================== CHROMIUM ARTICLE PAGE CONTENT ====================
                        source={}
                        requestedUrl={}
                        finalUrl={}
                        title={}
                        status={}
                        challenge={}
                        htmlLength={}
                        textLength={}
                        PAGE TEXT:
                        ---
                        {}
                        ---
                        ================== END CHROMIUM ARTICLE PAGE CONTENT ==================
                        """,
                        source.getSlug(),
                        articleUrl,
                        page.url(),
                        page.title(),
                        page.status(),
                        page.challenge(),
                        page.html() == null ? 0 : page.html().length(),
                        page.text() == null ? 0 : page.text().length(),
                        page.text() == null ? "" : page.text());

                ParsedEntry entry = toEntry(page, lookback);
                if (entry != null) {
                    entries.add(entry);
                }
            } catch (RuntimeException ex) {
                log.warn("Article fetch failed: source={} url={}: {}",
                        source.getSlug(), articleUrl, ex.getMessage());
            }
        }

        log.info("Chromium site crawl finished: source={} listings={} discovered={} fetched={} kept={}",
                source.getSlug(), listingCount, articleUrls.size(), fetched, entries.size());
        return entries;
    }

    private ParsedEntry toEntry(ChromiumBrowserClient.BrowserPage page, Instant lookback) {
        PageContentExtractor.ExtractedPage extracted =
                contentExtractor.extract(page.url(), page.html(), page.text());
        if (extracted.body() == null || extracted.body().isBlank()) {
            return null;
        }
        Instant published = extracted.publishedAt();
        if (published != null && lookback != null && published.isBefore(lookback)) {
            return null;
        }

        return ParsedEntry.builder(page.url())
                .canonicalUrl(page.url())
                .title(extracted.title() == null ? page.title() : extracted.title())
                .author(extracted.author())
                .publishedAt(published)
                .bodyText(extracted.body())
                .contentType("text/html")
                .httpStatus(page.status())
                .contentHash(Hashing.sha256Hex(extracted.body()))
                .robotsAllowed(true)
                .build();
    }
}

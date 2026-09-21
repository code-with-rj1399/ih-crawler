package ai.interviewhq.crawler.crawl.http;

import ai.interviewhq.crawler.browser.ChromiumBrowserClient;
import ai.interviewhq.crawler.crawl.FetchBlockedException;
import ai.interviewhq.crawler.crawl.robots.RobotsRules;
import ai.interviewhq.crawler.crawl.robots.RobotsService;
import ai.interviewhq.crawler.domain.CrawlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * HTTP + HTML parser first; Chromium only when the page is blocked, empty,
 * or a JS shell. Browser contexts are reused via {@link ChromiumBrowserClient.Session}.
 */
@Component
public class HybridPageFetcher {

    private static final Logger log = LoggerFactory.getLogger(HybridPageFetcher.class);

    private final PoliteFetcher http;
    private final ChromiumBrowserClient browser;
    private final RobotsService robotsService;

    public HybridPageFetcher(
            PoliteFetcher http,
            ChromiumBrowserClient browser,
            RobotsService robotsService
    ) {
        this.http = http;
        this.browser = browser;
        this.robotsService = robotsService;
    }

    public PageSnapshot fetch(CrawlSource source, String url, String referer) {
        return fetch(source, url, referer, FetchMode.from(source), 0, null);
    }

    public PageSnapshot fetch(
            CrawlSource source,
            String url,
            String referer,
            FetchMode mode,
            int listingScrolls,
            ChromiumBrowserClient.Session session
    ) {
        if (url == null || url.isBlank()) {
            return PageSnapshot.denied("", "blank url");
        }
        FetchMode resolved = mode == null ? FetchMode.from(source) : mode;

        PageSnapshot httpPage = null;
        if (resolved.allowsHttp() && !resolved.preferBrowser()) {
            httpPage = fetchHttp(source, url);
            if (httpPage.isSuccess() && !JsHeavyDetector.needsBrowser(httpPage)) {
                log.debug("HTTP fetch sufficient: url={} status={} text={}",
                        url, httpPage.status(), httpPage.text() == null ? 0 : httpPage.text().length());
                return httpPage;
            }
            if (!resolved.allowsBrowser()) {
                return httpPage;
            }
            log.info("HTTP insufficient, falling back to Chromium: url={} status={} jsHeavy={} blocked={}",
                    url,
                    httpPage.status(),
                    JsHeavyDetector.needsBrowser(httpPage),
                    httpPage.challenge() || httpPage.status() == 403 || httpPage.status() == 429);
        }

        if (!resolved.allowsBrowser()) {
            return httpPage == null ? PageSnapshot.denied(url, "http only") : httpPage;
        }

        PageSnapshot browserPage = fetchBrowser(source, url, referer, listingScrolls, session);
        if (browserPage.isSuccess()) {
            return browserPage;
        }

        if (resolved.preferBrowser() && resolved.allowsHttp()) {
            httpPage = fetchHttp(source, url);
            if (httpPage.isSuccess() && !JsHeavyDetector.needsBrowser(httpPage)) {
                log.info("Chromium missed, HTTP recovered: url={}", url);
                return httpPage;
            }
        }
        return browserPage.isSuccess() || httpPage == null ? browserPage : firstUseful(browserPage, httpPage);
    }

    private PageSnapshot fetchHttp(CrawlSource source, String url) {
        try {
            FetchResult result = http.get(source, url);
            return PageSnapshot.fromHttp(result);
        } catch (FetchBlockedException blocked) {
            log.info("HTTP blocked for {}: {}", url, blocked.getMessage());
            return new PageSnapshot(url, url, "", "", "", blocked.getStatus() == 0 ? 403 : blocked.getStatus(),
                    null, null, true, false, true, blocked.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("HTTP fetch interrupted for " + url, e);
        } catch (RuntimeException e) {
            log.warn("HTTP fetch failed for {}: {}", url, e.getMessage());
            return new PageSnapshot(url, url, "", "", "", 0, null, null, true, false, false, e.getMessage());
        }
    }

    private PageSnapshot fetchBrowser(
            CrawlSource source,
            String url,
            String referer,
            int listingScrolls,
            ChromiumBrowserClient.Session session
    ) {
        if (!robotsAllowed(source, url)) {
            return PageSnapshot.denied(url, "robots.txt disallows " + url);
        }
        ChromiumBrowserClient.BrowserPage page;
        if (session != null) {
            page = session.fetch(source, url, referer, listingScrolls);
        } else {
            page = browser.fetch(source, url, referer, listingScrolls);
        }
        PageSnapshot snapshot = PageSnapshot.fromBrowser(url, page);
        log.debug("Chromium fetch: url={} status={} challenge={} usedSession={}",
                url, snapshot.status(), snapshot.challenge(), session != null);
        return snapshot;
    }

    private boolean robotsAllowed(CrawlSource source, String url) {
        try {
            RobotsRules.Decision decision = robotsService.check(source, URI.create(url));
            return decision.allowed();
        } catch (RuntimeException e) {
            log.debug("robots check failed for {}: {}", url, e.getMessage());
            return true;
        }
    }

    private static PageSnapshot firstUseful(PageSnapshot browserPage, PageSnapshot httpPage) {
        int browserLen = browserPage.text() == null ? 0 : browserPage.text().length();
        int httpLen = httpPage.text() == null ? 0 : httpPage.text().length();
        return httpLen > browserLen ? httpPage : browserPage;
    }
}

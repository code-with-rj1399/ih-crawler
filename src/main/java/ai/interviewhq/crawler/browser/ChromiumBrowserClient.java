package ai.interviewhq.crawler.browser;

import ai.interviewhq.crawler.domain.CrawlSource;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Playwright Chromium fetcher. Discovery and page reads go through here so the
 * model never has to browse. Retries 403/429 with human-like waits and a
 * real-browser fingerprint to avoid bot walls.
 */
@Component
public class ChromiumBrowserClient {

    private static final Logger log = LoggerFactory.getLogger(ChromiumBrowserClient.class);

    private static final String STEALTH_JS = """
            Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
            window.chrome = window.chrome || { runtime: {} };
            Object.defineProperty(navigator, 'languages', { get: () => ['en-US', 'en'] });
            Object.defineProperty(navigator, 'plugins', { get: () => [1, 2, 3, 4, 5] });
            """;

    private static final String[] USER_AGENTS = {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    };

    private static final int[][] VIEWPORTS = {
            {1366, 768},
            {1440, 900},
            {1920, 1080},
            {1280, 800}
    };

    private final Playwright playwright;
    private final Browser browser;
    private final int navigationTimeoutMs;
    private final int maxRetries;
    private final int humanDelayMinMs;
    private final int humanDelayMaxMs;
    private final int blockRetryMinMs;
    private final int blockRetryMaxMs;
    private final boolean stealth;
    private final String configuredUserAgent;
    private final ConcurrentHashMap<String, Object> hostLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastFetchByHost = new ConcurrentHashMap<>();

    public ChromiumBrowserClient(
            @Value("${crawler.browser.headless:true}") boolean headless,
            @Value("${crawler.browser.navigation-timeout-ms:30000}") int navigationTimeoutMs,
            @Value("${crawler.browser.max-retries:4}") int maxRetries,
            @Value("${crawler.browser.human-delay-min-ms:2500}") int humanDelayMinMs,
            @Value("${crawler.browser.human-delay-max-ms:8000}") int humanDelayMaxMs,
            @Value("${crawler.browser.block-retry-min-ms:12000}") int blockRetryMinMs,
            @Value("${crawler.browser.block-retry-max-ms:40000}") int blockRetryMaxMs,
            @Value("${crawler.browser.stealth:true}") boolean stealth,
            @Value("${crawler.browser.user-agent:}") String configuredUserAgent,
            @Value("${CHROMIUM_EXECUTABLE_PATH:}") String executablePath) {

        this.navigationTimeoutMs = navigationTimeoutMs;
        this.maxRetries = Math.max(0, maxRetries);
        this.humanDelayMinMs = Math.max(0, humanDelayMinMs);
        this.humanDelayMaxMs = Math.max(this.humanDelayMinMs, humanDelayMaxMs);
        this.blockRetryMinMs = Math.max(1000, blockRetryMinMs);
        this.blockRetryMaxMs = Math.max(this.blockRetryMinMs, blockRetryMaxMs);
        this.stealth = stealth;
        this.configuredUserAgent = configuredUserAgent;
        this.playwright = Playwright.create();

        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless)
                .setArgs(List.of(
                        "--disable-blink-features=AutomationControlled",
                        "--disable-dev-shm-usage",
                        "--no-sandbox",
                        "--disable-infobars",
                        "--disable-extensions"
                ));

        if (executablePath != null && !executablePath.isBlank()) {
            options.setExecutablePath(java.nio.file.Path.of(executablePath));
        }

        this.browser = playwright.chromium().launch(options);
    }

    public BrowserPage fetch(CrawlSource source, String url) {
        return fetch(source, url, null, 0);
    }

    public BrowserPage fetch(CrawlSource source, String url, String referer) {
        return fetch(source, url, referer, 0);
    }

    public BrowserPage fetch(CrawlSource source, String url, String referer, int scrollPasses) {
        try (Session session = openSession()) {
            return session.fetch(source, url, referer, scrollPasses);
        }
    }

    public Session openSession() {
        return new Session();
    }

    /**
     * Reuses one browser context across sequential same-host fetches. Fresh
     * context is created only on retries / challenges.
     */
    public final class Session implements AutoCloseable {
        private BrowserContext context;
        private Page page;

        public BrowserPage fetch(CrawlSource source, String url, String referer) {
            return fetch(source, url, referer, 0);
        }

        public BrowserPage fetch(CrawlSource source, String url, String referer, int scrollPasses) {
            Objects.requireNonNull(url, "url");
            String host = hostOf(url);
            Object lock = hostLocks.computeIfAbsent(host, key -> new Object());
            synchronized (lock) {
                BrowserPage last = empty(url, 0);
                for (int attempt = 0; attempt <= maxRetries; attempt++) {
                    try {
                        honorHostGap(source, host);
                        last = navigateOnce(url, referer, attempt, Math.max(0, scrollPasses));
                        if (last.isSuccess()) {
                            return last;
                        }
                        resetContext();
                        if (isRetryable(last) && attempt < maxRetries) {
                            long waitMs = HumanDelay.exponentialJitter(attempt, blockRetryMinMs, blockRetryMaxMs);
                            log.info("Chromium retry {}/{} url={} status={} challenge={} wait={}ms",
                                    attempt + 1, maxRetries, url, last.status(), last.challenge(), waitMs);
                            Thread.sleep(waitMs);
                            continue;
                        }
                        return last;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Chromium fetch interrupted for " + url, e);
                    } catch (RuntimeException e) {
                        log.warn("Chromium fetch error attempt {} for {}: {}", attempt, url, e.getMessage());
                        resetContext();
                        last = empty(url, 0);
                        if (attempt < maxRetries) {
                            try {
                                Thread.sleep(HumanDelay.exponentialJitter(attempt, blockRetryMinMs, blockRetryMaxMs));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException("Chromium fetch interrupted for " + url, ie);
                            }
                        }
                    }
                }
                return last;
            }
        }

        private BrowserPage navigateOnce(String url, String referer, int attempt, int scrollPasses) {
            ensureContext(referer, attempt);
            Response response = null;
            try {
                response = page.navigate(url, new Page.NavigateOptions()
                        .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                        .setTimeout(navigationTimeoutMs));
            } catch (PlaywrightException ex) {
                log.debug("navigation wait incomplete for {}: {}", url, ex.getMessage());
            }

            waitForLeetcodePosts(source, page);
            behaveLikeReader(page);
            extraScrolls(page, scrollPasses);

            int status = response == null ? guessStatus(page) : response.status();
            String html = safe(() -> page.content(), "");
            String text = safe(() -> page.locator("body").innerText(), "");
            String title = safe(page::title, "");
            String finalUrl = safe(page::url, url);
            boolean challenge = looksLikeChallenge(title, html, text, status);
            return new BrowserPage(finalUrl, title, html, text, status, challenge);
        }

        private void ensureContext(String referer, int attempt) {
            if (context != null && page != null && attempt == 0) {
                return;
            }
            resetContext();
            int[] viewport = VIEWPORTS[ThreadLocalRandom.current().nextInt(VIEWPORTS.length)];
            String userAgent = userAgentForAttempt(attempt);
            context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent(userAgent)
                    .setViewportSize(viewport[0], viewport[1])
                    .setLocale("en-US")
                    .setTimezoneId("America/New_York")
                    .setExtraHTTPHeaders(headers(referer, userAgent)));
            if (stealth) {
                context.addInitScript(STEALTH_JS);
            }
            page = context.newPage();
            page.setDefaultNavigationTimeout(navigationTimeoutMs);
            page.setDefaultTimeout(navigationTimeoutMs);
        }

        private void resetContext() {
            if (page != null) {
                try {
                    page.close();
                } catch (RuntimeException ignored) {
                }
                page = null;
            }
            if (context != null) {
                try {
                    context.close();
                } catch (RuntimeException ignored) {
                }
                context = null;
            }
        }

        @Override
        public void close() {
            resetContext();
        }
    }

    private void waitForLeetcodePosts(CrawlSource source, Page page) {
        if (source == null || page == null || !"leetcode-interviews".equals(source.getSlug())) {
            return;
        }
        try {
            // LeetCode Discuss is a JS-rendered page. DOMContentLoaded only gives
            // us the application shell, before the post links are attached.
            page.waitForSelector("a[href*='/discuss/post/']",
                    new Page.WaitForSelectorOptions()
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED)
                            .setTimeout(8000));
            log.debug("LeetCode interview post links rendered: url={}", page.url());
        } catch (PlaywrightException ex) {
            log.warn("LeetCode interview post links did not render before timeout: url={}", page.url());
        }
    }

    private void extraScrolls(Page page, int scrollPasses) {
        if (scrollPasses <= 0 || page == null) {
            return;
        }
        for (int i = 0; i < scrollPasses; i++) {
            try {
                page.evaluate("() => window.scrollTo(0, document.body.scrollHeight)");
                clickIfPresent(page, "text=Load more");
                clickIfPresent(page, "text=Show more");
                clickIfPresent(page, "text=See more");
                clickIfPresent(page, "text=Next");
                page.waitForTimeout(HumanDelay.between(350, 900));
            } catch (RuntimeException ignored) {
                return;
            }
        }
    }

    private static void clickIfPresent(Page page, String selector) {
        try {
            var locator = page.locator(selector).first();
            if (locator.count() > 0) {
                locator.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(800));
            }
        } catch (RuntimeException ignored) {
            // Listing pagination is best-effort.
        }
    }

    private void behaveLikeReader(Page page) {
        try {
            page.waitForTimeout(HumanDelay.between(400, 1200));
            int hops = ThreadLocalRandom.current().nextInt(2, 5);
            for (int i = 0; i < hops; i++) {
                int x = ThreadLocalRandom.current().nextInt(80, 1100);
                int y = ThreadLocalRandom.current().nextInt(80, 700);
                page.mouse().move(x, y);
                page.evaluate("() => window.scrollBy(0, Math.floor(window.innerHeight * (0.2 + Math.random() * 0.45)))");
                page.waitForTimeout(HumanDelay.between(180, 520));
            }
        } catch (RuntimeException ignored) {
            // Humanization is best-effort; content still counts.
        }
    }

    private Map<String, String> headers(String referer, String userAgent) {
        java.util.LinkedHashMap<String, String> headers = new java.util.LinkedHashMap<>();
        headers.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        headers.put("Cache-Control", "max-age=0");
        headers.put("Upgrade-Insecure-Requests", "1");
        headers.put("Sec-Fetch-Dest", "document");
        headers.put("Sec-Fetch-Mode", "navigate");
        headers.put("Sec-Fetch-Site", referer == null || referer.isBlank() ? "none" : "same-origin");
        headers.put("Sec-Fetch-User", "?1");
        headers.put("sec-ch-ua", "\"Chromium\";v=\"131\", \"Not_A Brand\";v=\"24\", \"Google Chrome\";v=\"131\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("sec-ch-ua-platform", "\"Windows\"");
        if (referer != null && !referer.isBlank()) {
            headers.put("Referer", referer);
        }
        headers.put("User-Agent", userAgent);
        return headers;
    }

    private String userAgentForAttempt(int attempt) {
        if (configuredUserAgent != null && !configuredUserAgent.isBlank() && attempt == 0) {
            return configuredUserAgent;
        }
        return USER_AGENTS[attempt % USER_AGENTS.length];
    }

    private void honorHostGap(CrawlSource source, String host) throws InterruptedException {
        int configured = source == null ? 0 : Math.max(0, source.getCrawlDelayMs());
        long gap = Math.max(configured, HumanDelay.between(humanDelayMinMs, humanDelayMaxMs));
        Long last = lastFetchByHost.get(host);
        long now = System.currentTimeMillis();
        if (last != null) {
            long wait = last + gap - now;
            if (wait > 0) {
                log.debug("human delay {}ms before next Chromium fetch host={}", wait, host);
                Thread.sleep(wait);
            }
        }
        lastFetchByHost.put(host, System.currentTimeMillis());
    }

    private static boolean isRetryable(BrowserPage page) {
        if (page == null) {
            return true;
        }
        if (page.challenge()) {
            return true;
        }
        int status = page.status();
        return status == 0 || status == 403 || status == 408 || status == 425
                || status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
    }

    public static boolean looksLikeChallenge(String title, String html, String text, int status) {
        if (status == 403 || status == 429) {
            return true;
        }
        String blob = ((title == null ? "" : title) + "\n" + (html == null ? "" : html)
                + "\n" + (text == null ? "" : text)).toLowerCase();
        return blob.contains("just a moment")
                || blob.contains("attention required")
                || blob.contains("access denied")
                || blob.contains("verify you are human")
                || blob.contains("cf-browser-verification")
                || blob.contains("checking your browser")
                || blob.contains("enable javascript and cookies")
                || blob.contains("unusually high number of requests");
    }

    private static int guessStatus(Page page) {
        try {
            Object value = page.evaluate("() => document.body && document.body.innerText ? 200 : 0");
            if (value instanceof Number number) {
                return number.intValue();
            }
        } catch (RuntimeException ignored) {
        }
        return 0;
    }

    private static String safe(SupplierWithDefault supplier, String fallback) {
        try {
            String value = supplier.get();
            return value == null ? fallback : value;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static BrowserPage empty(String url, int status) {
        return new BrowserPage(url, "", "", "", status, status == 403 || status == 429);
    }

    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? url : host.toLowerCase();
        } catch (Exception e) {
            return url;
        }
    }

    @PreDestroy
    public void close() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @FunctionalInterface
    private interface SupplierWithDefault {
        String get();
    }

    public record BrowserPage(
            String url,
            String title,
            String html,
            String text,
            int status,
            boolean challenge
    ) {
        public boolean isSuccess() {
            return status >= 200 && status < 400 && !challenge && html != null && !html.isBlank();
        }
    }
}

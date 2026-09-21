package ai.interviewhq.crawler.browser;

import ai.interviewhq.crawler.domain.CrawlSource;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Objects;

@Component
public class ChromiumBrowserClient {

    private final Playwright playwright;
    private final Browser browser;
    private final int navigationTimeoutMs;

    public ChromiumBrowserClient(
            @Value("${crawler.browser.headless:true}") boolean headless,
            @Value("${crawler.browser.navigation-timeout-ms:30000}") int navigationTimeoutMs,
            @Value("${CHROMIUM_EXECUTABLE_PATH:}") String executablePath) {

        this.navigationTimeoutMs = navigationTimeoutMs;
        this.playwright = Playwright.create();

        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setHeadless(headless);

        if (executablePath != null && !executablePath.isBlank()) {
            options.setExecutablePath(java.nio.file.Path.of(executablePath));
        }

        this.browser = playwright.chromium().launch(options);
    }

    public BrowserPage fetch(CrawlSource source, String url) {
        Objects.requireNonNull(url, "url");

        BrowserContext context = browser.newContext(
                new Browser.NewContextOptions()
                        .setUserAgent("InterviewHQBot/1.0 (ih-crawler; +https://interviewhq.ai/bot)")
                );
        Page page = context.newPage();
        page.setDefaultNavigationTimeout(navigationTimeoutMs);
        page.setDefaultTimeout(navigationTimeoutMs);

        try {
            page.navigate(url, new Page.NavigateOptions()
                    .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                    .setTimeout(navigationTimeoutMs));

            // Give client-side rendered content a short window to settle without
            // introducing a large fixed delay for every page.
            page.waitForTimeout(750);

            return new BrowserPage(
                    page.url(),
                    page.title(),
                    page.content(),
                    page.locator("body").innerText()
            );
        } finally {
            page.close();
            context.close();
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

    public record BrowserPage(
            String url,
            String title,
            String html,
            String text
    ) {}
}

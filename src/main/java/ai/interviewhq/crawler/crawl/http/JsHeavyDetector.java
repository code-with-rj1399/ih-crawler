package ai.interviewhq.crawler.crawl.http;

import ai.interviewhq.crawler.browser.ChromiumBrowserClient;

import java.util.Locale;

/**
 * Cheap heuristic: HTML that is an SPA shell or bot-challenge should fall back
 * to Chromium instead of being parsed as an article.
 */
public final class JsHeavyDetector {

    private JsHeavyDetector() {
    }

    public static boolean needsBrowser(PageSnapshot page) {
        if (page == null) {
            return true;
        }
        return needsBrowser(page.html(), page.text(), page.title(), page.status(), page.challenge());
    }

    public static boolean needsBrowser(String html, String text, String title, int status, boolean challenge) {
        if (challenge || ChromiumBrowserClient.looksLikeChallenge(title, html, text, status)) {
            return true;
        }
        if (status == 401 || status == 403 || status == 429) {
            return true;
        }
        if (html == null || html.isBlank()) {
            return true;
        }
        String body = text == null ? "" : text.trim();
        String lower = html.toLowerCase(Locale.ROOT);
        if (body.length() < 120 && html.length() > 1500) {
            return true;
        }
        boolean spaRoot = containsAny(lower,
                "id=\"root\"", "id='root'", "id=\"__next\"", "id='__next'",
                "id=\"app\"", "id='app'", "ng-version=", "data-reactroot");
        if (spaRoot && body.length() < 400) {
            return true;
        }
        return containsAny(lower, "enable javascript", "you need to enable javascript")
                && body.length() < 400;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}

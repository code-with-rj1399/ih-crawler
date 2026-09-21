package ai.interviewhq.crawler.crawl.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsHeavyDetectorTest {

    @Test
    void spaShellAndChallengesNeedBrowser() {
        assertTrue(JsHeavyDetector.needsBrowser(
                "<html><body><div id=\"__next\"></div></body></html>",
                "", "Home", 200, false));
        assertTrue(JsHeavyDetector.needsBrowser(
                "<html>Just a moment</html>", "", "Just a moment...", 200, false));
        assertTrue(JsHeavyDetector.needsBrowser("", "", "Blocked", 403, false));
    }

    @Test
    void staticArticleDoesNotNeedBrowser() {
        String body = "They asked me two coding questions about LRU cache and system design of a news feed. "
                + "I talked through rate limiting, sharding, and fanout. This is a real interview write-up.";
        assertFalse(JsHeavyDetector.needsBrowser(
                "<html><article><p>" + body + "</p></article></html>",
                body,
                "Google SDE interview experience",
                200,
                false));
    }
}

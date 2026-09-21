package ai.interviewhq.crawler.browser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChromiumBrowserClientTest {

    @Test
    void detectsCloudflareAndAccessDeniedPages() {
        assertTrue(ChromiumBrowserClient.looksLikeChallenge(
                "Just a moment...", "<html>checking your browser</html>", "", 200));
        assertTrue(ChromiumBrowserClient.looksLikeChallenge("Home", "", "", 403));
        assertFalse(ChromiumBrowserClient.looksLikeChallenge(
                "Google Interview Experience", "<article>asked me two coding questions</article>",
                "asked me two coding questions", 200));
    }
}

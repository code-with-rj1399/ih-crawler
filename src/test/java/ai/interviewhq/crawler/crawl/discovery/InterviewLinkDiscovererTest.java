package ai.interviewhq.crawler.crawl.discovery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterviewLinkDiscovererTest {

    private final InterviewLinkDiscoverer discoverer = new InterviewLinkDiscoverer();

    @Test
    void keepsInterviewArticlesAndDropsLoginAssets() {
        String html = """
                <html><body>
                  <a href="/interview-experience/google-sde-2">Google SDE Interview Experience</a>
                  <a href="/blog/my-amazon-onsite-loop">My Amazon onsite</a>
                  <a href="/login">Login</a>
                  <a href="/privacy">Privacy</a>
                  <a href="/logo.png">logo</a>
                  <a href="https://ads.example.com/click">Ad</a>
                  <a rel="next" href="/page/2">Next</a>
                </body></html>
                """;

        List<InterviewLinkDiscoverer.DiscoveredLink> links =
                discoverer.discover("https://www.geeksforgeeks.org/category/experiences/", html, 20);

        assertTrue(links.stream().anyMatch(l -> l.url().contains("google-sde-2")));
        assertTrue(links.stream().anyMatch(l -> l.url().contains("amazon-onsite")));
        assertFalse(links.stream().anyMatch(l -> l.url().contains("login")));
        assertFalse(links.stream().anyMatch(l -> l.url().contains("privacy")));
        assertFalse(links.stream().anyMatch(l -> l.url().contains("ads.example.com")));

        List<String> pages = discoverer.paginationUrls(
                "https://www.geeksforgeeks.org/category/experiences/", html);
        assertTrue(pages.stream().anyMatch(u -> u.contains("/page/2")));
    }

    @Test
    void leetcodeKeepsOnlyDiscussPostUrls() {
        String html = """
                <html><body>
                  <a href="https://leetcode.com/">Copyright © 2026 LeetCode</a>
                  <a href="https://leetcode.com/discuss/create/">Create</a>
                  <a href="https://leetcode.com/problemset">Problems</a>
                  <a href="https://leetcode.com/contest">Contest</a>
                  <a href="https://leetcode.com/discuss/post/8014509/amazon-sde-1-interview-experience">Amazon SDE-1 Interview Experience</a>
                  <a href="https://leetcode.com/discuss/interview-experience/3276890/oracle">Legacy interview experience</a>
                </body></html>
                """;

        List<InterviewLinkDiscoverer.DiscoveredLink> links =
                discoverer.discover("https://leetcode.com/discuss/", html, 20);

        assertTrue(links.stream().anyMatch(l -> l.url().contains("/discuss/post/8014509/")));
        assertTrue(links.stream().anyMatch(l -> l.url().contains("/discuss/interview-experience/3276890/")));
        assertFalse(links.stream().anyMatch(l -> l.url().equals("https://leetcode.com/")));
        assertFalse(links.stream().anyMatch(l -> l.url().contains("/discuss/create/")));
        assertFalse(links.stream().anyMatch(l -> l.url().contains("/problemset")));
        assertFalse(links.stream().anyMatch(l -> l.url().contains("/contest")));
    }

    @Test
    void scoresInterviewUrlsHigherThanBarePaths() {
        int interview = InterviewLinkDiscoverer.score(
                "https://dev.to/jane/google-interview-experience", "Google interview experience", true);
        int generic = InterviewLinkDiscoverer.score(
                "https://dev.to/about", "About", true);
        assertTrue(interview > generic);
    }
}

package ai.interviewhq.crawler.config;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SeedCatalogTest {

    @Test
    void enabledSeedsAreHttpsInterviewSourcesWithUniqueSlugs() {
        Set<String> slugs = new HashSet<>();
        for (SeedCatalog.Seed seed : SeedCatalog.all()) {
            assertTrue(slugs.add(seed.slug()), "duplicate slug " + seed.slug());
            assertTrue(seed.url().startsWith("https://"), seed.slug());
            assertFalse(seed.kind() == null || seed.kind().isBlank(), seed.slug());
            assertTrue(seed.rpm() >= 1, seed.slug());
            assertTrue(seed.delayMs() >= 1000, seed.slug());
        }

        Set<String> enabled = new HashSet<>();
        SeedCatalog.enabled().forEach(seed -> enabled.add(seed.slug()));

        assertTrue(enabled.contains("leetcode-interviews"));
        assertTrue(enabled.contains("hacker-news-interviews"));
        assertTrue(enabled.contains("geeksforgeeks-interviews"));
        assertTrue(enabled.contains("devto-interview-feed"));
        assertTrue(enabled.contains("reddit-cscareerquestions"));
        assertTrue(enabled.contains("medium-software-interviews"));
        assertTrue(enabled.contains("hashnode-interview"));

        assertFalse(enabled.contains("interviewbit-questions"));
        assertFalse(enabled.contains("codeforces-problems"));
        assertFalse(enabled.contains("stackoverflow-interview-questions"));
        assertFalse(enabled.contains("hackerrank-interview-prep"));

        assertTrue(SeedCatalog.enabled().size() >= 8);
        assertEquals("rss", slug("reddit-experienced-devs").kind());
        assertTrue(slug("hacker-news-interviews").url().contains("search_by_date"));
        assertTrue(slug("medium-software-interviews").url().contains("/feed/"));
        assertEquals("https://dev.to/t/interview", slug("devto-interview").url());
    }

    private static SeedCatalog.Seed slug(String slug) {
        return SeedCatalog.all().stream()
                .filter(seed -> slug.equals(seed.slug()))
                .findFirst()
                .orElseThrow();
    }
}

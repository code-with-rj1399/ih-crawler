package ai.interviewhq.crawler.crawl.discovery;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PageContentExtractorTest {

    private final PageContentExtractor extractor = new PageContentExtractor();

    @Test
    void extractsArticleBodyTitleAndPublishedTime() {
        String html = """
                <html>
                  <head>
                    <meta property="og:title" content="Meta E5 interview">
                    <meta property="article:published_time" content="2026-09-18T10:00:00Z">
                    <meta name="author" content="Alex">
                  </head>
                  <body>
                    <nav>Home Jobs</nav>
                    <article>
                      <h1>Meta E5 interview</h1>
                      <p>They asked me to design Instagram feed and then a coding round on LRU cache.
                      I talked through rate limiting and fanout. This is enough text to pass the body threshold
                      used by the extractor so the article selector wins over the whole page.</p>
                    </article>
                    <footer>copyright</footer>
                  </body>
                </html>
                """;

        PageContentExtractor.ExtractedPage page = extractor.extract(
                "https://example.com/meta-e5", html, "fallback");

        assertEquals("Meta E5 interview", page.title());
        assertEquals("Alex", page.author());
        assertEquals(Instant.parse("2026-09-18T10:00:00Z"), page.publishedAt());
        assertTrue(page.body().contains("LRU cache"));
        assertTrue(page.body().toLowerCase().contains("instagram"));
    }
}

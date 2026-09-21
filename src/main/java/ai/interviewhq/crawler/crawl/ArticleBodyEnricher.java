package ai.interviewhq.crawler.crawl;

import ai.interviewhq.crawler.crawl.discovery.PageContentExtractor;
import ai.interviewhq.crawler.crawl.http.HybridPageFetcher;
import ai.interviewhq.crawler.crawl.http.PageSnapshot;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.util.Hashing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * RSS/JSON adapters often only have a title or snippet. Fetch the article
 * with HTTP-first hybrid fetching when the body is too thin to extract from.
 */
@Component
public class ArticleBodyEnricher {

    private static final Logger log = LoggerFactory.getLogger(ArticleBodyEnricher.class);
    private static final int THIN_BODY_CHARS = 280;

    private final HybridPageFetcher hybrid;
    private final PageContentExtractor extractor = new PageContentExtractor();

    public ArticleBodyEnricher(HybridPageFetcher hybrid) {
        this.hybrid = hybrid;
    }

    public ParsedEntry enrich(CrawlSource source, ParsedEntry entry) {
        if (entry == null) {
            return null;
        }
        boolean thin = entry.bodyText() == null || entry.bodyText().isBlank()
                || entry.bodyText().length() < THIN_BODY_CHARS;
        boolean missingDate = entry.publishedAt() == null;
        if (!thin && !missingDate) {
            return entry;
        }

        String url = firstNonBlank(entry.canonicalUrl(), entry.url());
        if (url == null || url.isBlank()) {
            return entry;
        }

        PageSnapshot page = hybrid.fetch(source, url, source == null ? null : source.getUrl());
        if (!page.isSuccess()) {
            log.info("Article enrich skipped: url={} status={} challenge={}",
                    url, page.status(), page.challenge());
            return entry;
        }

        PageContentExtractor.ExtractedPage extracted =
                extractor.extract(page.finalUrl(), page.html(), page.text());
        String body = longer(entry.bodyText(), extracted.body());
        Instant published = entry.publishedAt() != null ? entry.publishedAt() : extracted.publishedAt();
        String title = firstNonBlank(entry.title(), extracted.title(), page.title());
        String author = firstNonBlank(entry.author(), extracted.author());
        String canonical = firstNonBlank(page.finalUrl(), entry.canonicalUrl(), entry.url());

        return ParsedEntry.builder(entry.url())
                .canonicalUrl(canonical)
                .externalId(entry.externalId())
                .title(title)
                .author(author)
                .publishedAt(published)
                .rawCompany(entry.rawCompany())
                .rawRole(entry.rawRole())
                .bodyText(body)
                .contentType(page.contentType() == null ? entry.contentType() : page.contentType())
                .httpStatus(page.status())
                .etag(page.etag() == null ? entry.etag() : page.etag())
                .contentHash(Hashing.sha256Hex(body == null ? url : body))
                .robotsAllowed(page.robotsAllowed())
                .build();
    }

    private static String longer(String a, String b) {
        int la = a == null ? 0 : a.length();
        int lb = b == null ? 0 : b.length();
        if (lb > la) {
            return b;
        }
        return a;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

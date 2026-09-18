package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.crawl.SourceAdapter;
import ai.interviewhq.crawler.crawl.http.FetchResult;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.util.Hashing;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class BlindAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(BlindAdapter.class);

    @Override
    public String kind() {
        return "blind";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception {
        FetchResult result;
        try {
            result = fetcher.get(source, source.getUrl());
        } catch (Exception ex) {
            log.warn("blind blocked/fail-closed for {}: {}", source.getSlug(), ex.getMessage());
            throw ex;
        }
        if (!result.robotsAllowed()) {
            return List.of();
        }
        if (!result.isSuccess()) {
            log.info("blind HTTP {} for {} — fail closed", result.status(), source.getSlug());
            return List.of();
        }
        Document doc = Jsoup.parse(result.bodyAsString(), source.getUrl());
        List<ParsedEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select("a[href*=/post/], a[href*=/s/], article a[href], h2 a[href], h3 a[href]")) {
            String href = link.absUrl("href");
            String title = link.text();
            if (href.isBlank() || title.isBlank() || title.length() < 8) {
                continue;
            }
            if (!href.contains("teamblind.com") && !href.contains("/post/") && !href.contains("/s/")) {
                continue;
            }
            if (!seen.add(href)) {
                continue;
            }
            entries.add(ParsedEntry.builder(href)
                    .canonicalUrl(href)
                    .title(title)
                    .publishedAt(null)
                    .bodyText(title)
                    .contentType(result.contentType())
                    .httpStatus(result.status())
                    .etag(result.etag())
                    .contentHash(Hashing.sha256Hex(href + title))
                    .robotsAllowed(true)
                    .build());
            if (entries.size() >= 20) {
                break;
            }
        }
        if (entries.isEmpty()) {
            log.info("blind HTML contained no public posts for {} (likely gated)", source.getSlug());
        }
        return entries;
    }
}

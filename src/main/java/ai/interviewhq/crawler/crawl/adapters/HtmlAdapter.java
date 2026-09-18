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
public class HtmlAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(HtmlAdapter.class);

    @Override
    public String kind() {
        return "html";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception {
        FetchResult listing = fetcher.get(source, source.getUrl());
        if (!listing.robotsAllowed() || listing.isNotModified()) {
            return List.of();
        }
        if (!listing.isSuccess()) {
            throw new IllegalStateException("HTML listing HTTP " + listing.status());
        }
        String itemSelector = ParserConfigs.text(source, "itemSelector", "article a, .post-title a, h2 a");
        String titleSelector = ParserConfigs.text(source, "titleSelector", "h1, h2");
        String bodySelector = ParserConfigs.text(source, "bodySelector", "article, .post-content, .entry-content");

        Document doc = Jsoup.parse(listing.bodyAsString(), source.getUrl());
        List<ParsedEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element link : doc.select(itemSelector)) {
            String href = link.absUrl("href");
            if (href.isBlank() || !seen.add(href)) {
                continue;
            }
            String title = link.text();
            String body = title;
            try {
                FetchResult page = fetcher.get(source, href);
                if (page.isSuccess() && page.robotsAllowed()) {
                    Document article = Jsoup.parse(page.bodyAsString(), href);
                    Element titleEl = article.selectFirst(titleSelector);
                    if (titleEl != null && !titleEl.text().isBlank()) {
                        title = titleEl.text();
                    }
                    Element bodyEl = article.selectFirst(bodySelector);
                    if (bodyEl != null) {
                        body = bodyEl.text();
                    } else {
                        body = article.body() == null ? title : article.body().text();
                    }
                    entries.add(ParsedEntry.builder(href)
                            .canonicalUrl(href)
                            .title(title)
                            .publishedAt(null)
                            .bodyText(body)
                            .contentType(page.contentType())
                            .httpStatus(page.status())
                            .etag(page.etag())
                            .contentHash(Hashing.sha256Hex(body == null ? href : body))
                            .robotsAllowed(true)
                            .build());
                }
            } catch (Exception ex) {
                log.info("html adapter skipped detail {} ({})", href, ex.getMessage());
                entries.add(ParsedEntry.builder(href)
                        .canonicalUrl(href)
                        .title(title)
                        .bodyText(title)
                        .httpStatus(listing.status())
                        .contentHash(Hashing.sha256Hex(href + title))
                        .robotsAllowed(true)
                        .build());
            }
            if (entries.size() >= 12) {
                break;
            }
        }
        return entries;
    }
}

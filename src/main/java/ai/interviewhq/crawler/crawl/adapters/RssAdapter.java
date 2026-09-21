package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.crawl.ArticleBodyEnricher;
import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.crawl.SourceAdapter;
import ai.interviewhq.crawler.crawl.http.FetchResult;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.util.Hashing;
import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

@Component
public class RssAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(RssAdapter.class);
    private static final int ENRICH_CAP = 12;

    private final ArticleBodyEnricher enricher;

    public RssAdapter(ArticleBodyEnricher enricher) {
        this.enricher = enricher;
    }

    @Override
    public String kind() {
        return "rss";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception {
        FetchResult result = fetcher.get(source, source.getUrl());
        if (!result.robotsAllowed()) {
            return List.of();
        }
        if (result.isNotModified()) {
            return List.of();
        }
        if (!result.isSuccess()) {
            if (isHashnode(source) && result.status() == 404) {
                log.info("hashnode RSS 404, falling back to tag HTML {}", source.getUrl());
                return enrich(source, htmlFallback(source, lookback, fetcher));
            }
            throw new IllegalStateException("RSS fetch HTTP " + result.status() + " for " + source.getUrl());
        }
        try {
            return enrich(source, parseFeed(source, result, lookback));
        } catch (Exception ex) {
            log.warn("RSS parse failed for {}: {} — trying HTML fallback", source.getSlug(), ex.getMessage());
            return enrich(source, htmlFallback(source, lookback, fetcher));
        }
    }

    private List<ParsedEntry> parseFeed(CrawlSource source, FetchResult result, Instant lookback) throws Exception {
        SyndFeedInput input = new SyndFeedInput();
        try (XmlReader reader = new XmlReader(new ByteArrayInputStream(result.body() == null ? new byte[0] : result.body()))) {
            SyndFeed feed = input.build(reader);
            List<ParsedEntry> entries = new ArrayList<>();
            for (SyndEntry item : feed.getEntries()) {
                Instant published = published(item);
                if (published != null && published.isBefore(lookback)) {
                    continue;
                }
                String url = firstUrl(item);
                if (url == null || url.isBlank()) {
                    continue;
                }
                String body = bodyOf(item);
                entries.add(ParsedEntry.builder(url)
                        .canonicalUrl(url)
                        .externalId(item.getUri())
                        .title(item.getTitle())
                        .author(item.getAuthor())
                        .publishedAt(published)
                        .bodyText(body)
                        .contentType(result.contentType())
                        .httpStatus(result.status())
                        .etag(result.etag())
                        .contentHash(Hashing.sha256Hex(body == null ? url : body))
                        .robotsAllowed(true)
                        .build());
            }
            return entries;
        }
    }

    private List<ParsedEntry> htmlFallback(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception {
        String listing = source.getUrl()
                .replace("/rss", "")
                .replace("/feed/", "/")
                .replace("/feed", "/");
        FetchResult html = fetcher.get(source, listing);
        if (!html.isSuccess()) {
            return List.of();
        }
        Document doc = Jsoup.parse(html.bodyAsString(), listing);
        List<ParsedEntry> entries = new ArrayList<>();
        for (Element link : doc.select("article a[href], h2 a[href], h3 a[href], .post-title a[href]")) {
            String href = link.absUrl("href");
            if (href.isBlank()) {
                continue;
            }
            String title = link.text();
            if (title.isBlank()) {
                continue;
            }
            entries.add(ParsedEntry.builder(href)
                    .canonicalUrl(href)
                    .title(title)
                    .publishedAt(null)
                    .bodyText(title)
                    .contentType(html.contentType())
                    .httpStatus(html.status())
                    .contentHash(Hashing.sha256Hex(href + title))
                    .robotsAllowed(true)
                    .build());
            if (entries.size() >= 30) {
                break;
            }
        }
        return entries;
    }

    private List<ParsedEntry> enrich(CrawlSource source, List<ParsedEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        List<ParsedEntry> out = new ArrayList<>(entries.size());
        int enriched = 0;
        for (ParsedEntry entry : entries) {
            if (enriched < ENRICH_CAP) {
                ParsedEntry next = enricher.enrich(source, entry);
                if (next != entry) {
                    enriched++;
                }
                out.add(next);
            } else {
                out.add(entry);
            }
        }
        log.info("RSS enrich: source={} items={} fetchedFull={}", source.getSlug(), out.size(), enriched);
        return out;
    }

    private static boolean isHashnode(CrawlSource source) {
        return source.getUrl() != null && source.getUrl().toLowerCase(Locale.ROOT).contains("hashnode");
    }

    private static Instant published(SyndEntry item) {
        Date date = item.getPublishedDate() != null ? item.getPublishedDate() : item.getUpdatedDate();
        return date == null ? null : date.toInstant();
    }

    private static String firstUrl(SyndEntry item) {
        if (item.getLink() != null && !item.getLink().isBlank()) {
            return item.getLink();
        }
        if (item.getUri() != null && item.getUri().startsWith("http")) {
            return item.getUri();
        }
        if (item.getLinks() != null && !item.getLinks().isEmpty() && item.getLinks().get(0).getHref() != null) {
            return item.getLinks().get(0).getHref();
        }
        return null;
    }

    private static String bodyOf(SyndEntry item) {
        if (item.getDescription() != null && item.getDescription().getValue() != null) {
            return Jsoup.parse(item.getDescription().getValue()).text();
        }
        if (item.getContents() != null) {
            StringBuilder sb = new StringBuilder();
            for (SyndContent content : item.getContents()) {
                if (content.getValue() != null) {
                    if (!sb.isEmpty()) {
                        sb.append('\n');
                    }
                    sb.append(Jsoup.parse(content.getValue()).text());
                }
            }
            if (!sb.isEmpty()) {
                return sb.toString();
            }
        }
        return item.getTitle();
    }
}

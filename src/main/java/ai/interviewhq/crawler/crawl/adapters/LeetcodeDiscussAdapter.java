package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.browser.ChromiumBrowserClient;
import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.crawl.SourceAdapter;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.util.Hashing;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class LeetcodeDiscussAdapter implements SourceAdapter {

    private static final Pattern ISO_DATE = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z)"
    );

    private final ChromiumBrowserClient browser;

    public LeetcodeDiscussAdapter(ChromiumBrowserClient browser) {
        this.browser = browser;
    }

    @Override
    public String kind() {
        return "leetcode_discuss";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher)
            throws Exception {

        ChromiumBrowserClient.BrowserPage listing = browser.fetch(source, source.getUrl());
        Document listingDoc = Jsoup.parse(listing.html(), listing.url());

        List<String> urls = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Element link : listingDoc.select("a[href*='/discuss/post/']")) {
            String href = link.absUrl("href");
            if (href == null || href.isBlank() || !seen.add(href)) {
                continue;
            }
            urls.add(href);
            if (urls.size() >= 25) {
                break;
            }
        }

        List<ParsedEntry> entries = new ArrayList<>();

        for (String url : urls) {
            try {
                ChromiumBrowserClient.BrowserPage page = browser.fetch(source, url);
                ParsedEntry entry = parsePost(page, lookback);
                if (entry != null) {
                    entries.add(entry);
                }
            } catch (Exception ignored) {
                // A single unavailable post must not abort the source crawl.
            }
        }

        return entries;
    }

    private ParsedEntry parsePost(ChromiumBrowserClient.BrowserPage page, Instant lookback) {
        Document doc = Jsoup.parse(page.html(), page.url());

        Instant publishedAt = extractPublishedAt(doc);
        if (publishedAt != null && publishedAt.isBefore(lookback)) {
            return null;
        }

        String title = firstNonBlank(
                meta(doc, "meta[property=og:title]"),
                doc.select("h1").stream().findFirst().map(Element::text).orElse(page.title())
        );

        String author = firstNonBlank(
                meta(doc, "meta[name=author]"),
                doc.select("[class*=author] a, [class*=author]").stream()
                        .findFirst().map(Element::text).orElse(null)
        );

        Element main = doc.selectFirst(
                "main article, article, [data-testid*=post], [class*=article-content], [class*=discussion]"
        );

        String body = main != null ? main.text() : page.text();
        body = cleanBody(body);
        if (body.isBlank()) {
            return null;
        }

        return ParsedEntry.builder(page.url())
                .canonicalUrl(page.url())
                .externalId(extractExternalId(page.url()))
                .title(title)
                .author(author)
                .publishedAt(publishedAt)
                .bodyText(body)
                .contentType("text/html")
                .httpStatus(200)
                .contentHash(Hashing.sha256Hex(body))
                .robotsAllowed(true)
                .build();
    }

    private Instant extractPublishedAt(Document doc) {
        String[] selectors = {
                "meta[property=article:published_time]",
                "meta[property=og:published_time]",
                "meta[name=datePublished]",
                "time[datetime]"
        };

        for (String selector : selectors) {
            Element element = doc.selectFirst(selector);
            if (element == null) continue;

            String value = element.hasAttr("content")
                    ? element.attr("content")
                    : element.attr("datetime");

            Instant parsed = parseInstant(value);
            if (parsed != null) return parsed;
        }

        Matcher matcher = ISO_DATE.matcher(doc.html());
        return matcher.find() ? parseInstant(matcher.group(1)) : null;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) return null;

        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (Exception ignoredAgain) {
                return null;
            }
        }
    }

    private static String extractExternalId(String url) {
        try {
            String path = java.net.URI.create(url).getPath();
            if (path == null) return null;

            String[] parts = path.split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("post".equals(parts[i])) return parts[i + 1];
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String cleanBody(String body) {
        return body == null ? "" : body
                .replaceAll("\\n{3,}", "\\n\\n")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    private static String meta(Document doc, String selector) {
        Element element = doc.selectFirst(selector);
        if (element == null) return null;
        String content = element.attr("content");
        return content.isBlank() ? null : content.trim();
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}

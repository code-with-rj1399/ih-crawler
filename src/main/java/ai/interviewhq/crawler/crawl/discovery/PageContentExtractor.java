package ai.interviewhq.crawler.crawl.discovery;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pulls article title, body, and publication time from HTML without a model.
 */
public final class PageContentExtractor {

    public record ExtractedPage(String title, String author, Instant publishedAt, String body) {}

    private static final Pattern ISO_DATE = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:Z|[+-]\\d{2}:\\d{2}))");
    private static final Pattern JSON_LD_PUBLISHED = Pattern.compile(
            "\"datePublished\"\\s*:\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern JSON_LD_AUTHOR = Pattern.compile(
            "\"author\"\\s*:\\s*(?:\\{[^}]*\"name\"\\s*:\\s*\"([^\"]+)\"|\"([^\"]+)\")",
            Pattern.CASE_INSENSITIVE);

    private static final String[] BODY_SELECTORS = {
            "article",
            "main article",
            "[itemprop=articleBody]",
            ".post-content",
            ".article-content",
            ".entry-content",
            ".discussion-post",
            "[class*=article-body]",
            "[class*=post-body]",
            "[class*=postContent]",
            "[class*=question-content]",
            "main"
    };

    public ExtractedPage extract(String pageUrl, String html, String fallbackText) {
        if (html == null || html.isBlank()) {
            return new ExtractedPage(null, null, null, clean(fallbackText));
        }

        Document doc = Jsoup.parse(html, pageUrl == null ? "" : pageUrl);
        doc.select("script, style, noscript, iframe, svg, nav, footer, form").remove();

        String title = firstNonBlank(
                meta(doc, "meta[property=og:title]"),
                meta(doc, "meta[name=twitter:title]"),
                doc.selectFirst("h1") == null ? null : doc.selectFirst("h1").text(),
                doc.title()
        );

        String author = firstNonBlank(
                meta(doc, "meta[name=author]"),
                jsonLdGroup(html, JSON_LD_AUTHOR, 1, 2)
        );

        Instant publishedAt = firstInstant(
                meta(doc, "meta[property=article:published_time]"),
                meta(doc, "meta[property=og:published_time]"),
                meta(doc, "meta[name=datePublished]"),
                timeAttr(doc),
                jsonLdGroup(html, JSON_LD_PUBLISHED, 1)
        );

        String body = null;
        for (String selector : BODY_SELECTORS) {
            Element el = doc.selectFirst(selector);
            if (el == null) {
                continue;
            }
            String text = el.text();
            if (text != null && text.length() >= 80) {
                body = text;
                break;
            }
        }
        if (body == null || body.isBlank()) {
            body = fallbackText != null && !fallbackText.isBlank() ? fallbackText : doc.body() == null
                    ? "" : doc.body().text();
        }

        return new ExtractedPage(title, author, publishedAt, clean(body));
    }

    private static String timeAttr(Document doc) {
        Element time = doc.selectFirst("time[datetime]");
        return time == null ? null : time.attr("datetime");
    }

    private static Instant firstInstant(String... values) {
        for (String value : values) {
            Instant parsed = parseInstant(value);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String raw = value.trim();
        try {
            return Instant.parse(raw);
        } catch (Exception ignored) {
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception ignored) {
        }
        try {
            LocalDate date = LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE);
            return date.atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
        }
        Matcher matcher = ISO_DATE.matcher(raw);
        if (matcher.find()) {
            try {
                return Instant.parse(matcher.group(1));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static String jsonLdGroup(String html, Pattern pattern, int... groups) {
        Matcher matcher = pattern.matcher(html);
        if (!matcher.find()) {
            return null;
        }
        for (int group : groups) {
            if (group <= matcher.groupCount()) {
                String value = matcher.group(group);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static String meta(Document doc, String selector) {
        Element el = doc.selectFirst(selector);
        if (el == null) {
            return null;
        }
        String content = el.attr("content");
        return content == null || content.isBlank() ? null : content.trim();
    }

    private static String clean(String body) {
        if (body == null) {
            return "";
        }
        return body.replaceAll("\\n{3,}", "\n\n")
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}

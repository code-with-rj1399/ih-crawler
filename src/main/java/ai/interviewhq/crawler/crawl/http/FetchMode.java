package ai.interviewhq.crawler.crawl.http;

import ai.interviewhq.crawler.crawl.adapters.ParserConfigs;
import ai.interviewhq.crawler.domain.CrawlSource;

import java.util.Locale;

public enum FetchMode {
    HTTP_FIRST,
    BROWSER_FIRST,
    HTTP_ONLY,
    BROWSER_ONLY;

    public static FetchMode from(CrawlSource source) {
        String configured = ParserConfigs.text(source, "fetchMode", "");
        if (configured != null && !configured.isBlank()) {
            return parse(configured);
        }
        String kind = source == null || source.getSourceKind() == null
                ? ""
                : source.getSourceKind().toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "leetcode_discuss", "glassdoor", "blind" -> BROWSER_FIRST;
            case "rss", "hn_algolia", "reddit_json" -> HTTP_FIRST;
            default -> HTTP_FIRST;
        };
    }

    public static FetchMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return HTTP_FIRST;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "browser_first", "chromium_first", "browser" -> BROWSER_FIRST;
            case "http_only" -> HTTP_ONLY;
            case "browser_only", "chromium_only" -> BROWSER_ONLY;
            default -> HTTP_FIRST;
        };
    }

    public boolean allowsHttp() {
        return this != BROWSER_ONLY;
    }

    public boolean allowsBrowser() {
        return this != HTTP_ONLY;
    }

    public boolean preferBrowser() {
        return this == BROWSER_FIRST || this == BROWSER_ONLY;
    }
}

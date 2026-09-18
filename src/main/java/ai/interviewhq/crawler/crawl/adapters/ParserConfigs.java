package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.domain.CrawlSource;

import java.util.Map;

final class ParserConfigs {

    private ParserConfigs() {
    }

    static String text(CrawlSource source, String key, String fallback) {
        Map<String, Object> cfg = source.getParserConfig();
        if (cfg == null || !cfg.containsKey(key) || cfg.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(cfg.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    static String text(CrawlSource source, String key) {
        return text(source, key, null);
    }
}

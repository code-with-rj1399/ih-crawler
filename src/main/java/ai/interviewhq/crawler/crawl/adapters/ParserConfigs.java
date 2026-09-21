package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.domain.CrawlSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class ParserConfigs {

    private ParserConfigs() {
    }

    public static String text(CrawlSource source, String key, String fallback) {
        Map<String, Object> cfg = source == null ? null : source.getParserConfig();
        if (cfg == null || !cfg.containsKey(key) || cfg.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(cfg.get(key)).trim();
        return value.isEmpty() ? fallback : value;
    }

    public static String text(CrawlSource source, String key) {
        return text(source, key, null);
    }

    public static int integer(CrawlSource source, String key, int fallback) {
        String raw = text(source, key, null);
        if (raw == null) {
            Object value = value(source, key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            return fallback;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static boolean bool(CrawlSource source, String key, boolean fallback) {
        Object value = value(source, key);
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        String raw = String.valueOf(value).trim().toLowerCase();
        if (raw.equals("true") || raw.equals("1") || raw.equals("yes")) {
            return true;
        }
        if (raw.equals("false") || raw.equals("0") || raw.equals("no")) {
            return false;
        }
        return fallback;
    }

    public static List<String> strings(CrawlSource source, String key) {
        Object value = value(source, key);
        if (value == null) {
            return List.of();
        }
        if (value instanceof Collection<?> collection) {
            List<String> out = new ArrayList<>();
            for (Object item : collection) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
            return List.copyOf(out);
        }
        String raw = String.valueOf(value).trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        return List.of(raw.split("\\s*,\\s*"));
    }

    private static Object value(CrawlSource source, String key) {
        if (source == null || source.getParserConfig() == null) {
            return null;
        }
        return source.getParserConfig().get(key);
    }
}

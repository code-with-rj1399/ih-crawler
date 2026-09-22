package ai.interviewhq.crawler.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Development crawl seeds for interview-experience discovery.
 *
 * <p>These seeds intentionally cover multiple public communities rather than
 * relying on a single source. Company-specific URL templates are represented
 * in notes because the crawler needs concrete URLs before it can crawl them.
 */
public final class SeedCatalog {

    public record Seed(
            String slug,
            String name,
            String url,
            String kind,
            int rpm,
            int delayMs,
            boolean enabled,
            String notes,
            Map<String, Object> parserConfig
    ) {
    }

    private SeedCatalog() {
    }

    public static List<Seed> all() {
        List<Seed> seeds = new ArrayList<>();

        seeds.add(seed(
                "leetcode-discuss",
                "LeetCode Discuss",
                "https://leetcode.com/discuss/interview-question/",
                "leetcode_graphql",
                12,
                2000,
                true,
                "LeetCode interview-question discussions. Keep only genuine candidate interview experiences.",
                config("browser_first", 26, List.of("leetcode.com"), true, false)
        ));

        seeds.add(seed(
                "teamblind",
                "TeamBlind",
                "https://www.teamblind.com/",
                "html",
                12,
                2000,
                true,
                "Search for recent interview experience and company-specific interview discussions.",
                config("browser_first", 26, List.of("teamblind.com"), true, false)
        ));

        seeds.add(seed(
                "teamblind-search-interview-experience",
                "TeamBlind Interview Experience Search",
                "https://www.teamblind.com/search/interview%20experience",
                "html",
                12,
                2000,
                true,
                "TeamBlind search results for interview experience discussions.",
                config("browser_first", 26, List.of("teamblind.com"), true, false)
        ));

        seeds.add(seed(
                "geeksforgeeks-interview-experiences",
                "GeeksforGeeks Interview Experiences",
                "https://www.geeksforgeeks.org/category/experiences/interview-experiences/",
                "html",
                6,
                5000,
                true,
                "GeeksforGeeks interview experiences category. Crawl recent experience posts and extract only genuine interview questions.",
                config("browser_first", 26, List.of("geeksforgeeks.org"), true, false)
        ));

        seeds.add(seed(
                "teamblind-interview-experiences",
                "TeamBlind Interview Experiences",
                "https://www.teamblind.com/channels/interview-experiences",
                "html",
                12,
                2000,
                true,
                "TeamBlind interview-experiences channel.",
                config("browser_first", 26, List.of("teamblind.com"), true, false)
        ));

        return List.copyOf(seeds);
    }

    public static List<Seed> enabled() {
        return all().stream().filter(Seed::enabled).toList();
    }

    private static Seed seed(
            String slug,
            String name,
            String url,
            String kind,
            int rpm,
            int delayMs,
            boolean enabled,
            String notes,
            Map<String, Object> parserConfig
    ) {
        return new Seed(slug, name, url, kind, rpm, delayMs, enabled, notes, parserConfig);
    }

    private static Map<String, Object> config(
            String fetchMode,
            int freshnessHours,
            List<String> allowedHostSuffixes,
            boolean browserListing,
            boolean isFeed
    ) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("fetchMode", fetchMode);
        cfg.put("freshnessHours", freshnessHours);
        cfg.put("allowedHostSuffixes", List.copyOf(allowedHostSuffixes));
        cfg.put("browserListing", browserListing);
        cfg.put("feed", isFeed);
        cfg.put("maxListingDepth", 2);
        return cfg;
    }
}

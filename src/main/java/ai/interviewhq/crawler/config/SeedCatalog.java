package ai.interviewhq.crawler.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Verified crawl seeds. URLs were live-checked on 2026-09-21 (HTTP, no JS).
 *
 * <p>Working HTTP: HN Algolia, GFG listing, DEV.to HTML + RSS, Medium RSS,
 * Reddit Atom, Hashnode tag page.
 *
 * <p>HTTP blocked (kept for Chromium): LeetCode Discuss (Cloudflare 403).
 *
 * <p>Retired: problem catalogs / prep kits / Cloudflare-only low-signal pages
 * that are not interview experiences.
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
                "leetcode-interviews",
                "LeetCode Interview Experience",
                "https://leetcode.com/discuss/interview-experience/",
                "leetcode_discuss",
                20,
                4000,
                true,
                "URL is correct. Plain HTTP is Cloudflare 403; Chromium is required.",
                config("browser_first", 48, List.of("leetcode.com"), true, false)
        ));

        seeds.add(seed(
                "hacker-news-interviews",
                "Hacker News Interview Discussions",
                "https://hn.algolia.com/api/v1/search_by_date?query=interview%20experience&tags=story&hitsPerPage=30",
                "hn_algolia",
                30,
                2000,
                true,
                "HTTP 200 JSON. search_by_date is fresher than popularity search.",
                config("http_first", 48, List.of("news.ycombinator.com", "hn.algolia.com"), false, false)
        ));

        seeds.add(seed(
                "reddit-cscareerquestions",
                "Reddit cscareerquestions",
                "https://www.reddit.com/r/cscareerquestions/.rss",
                "rss",
                8,
                8000,
                true,
                "JSON endpoints 403. Official Atom feed returns 200 with dated items.",
                config("http_first", 48, List.of("reddit.com"), false, true)
        ));

        seeds.add(seed(
                "reddit-experienced-devs",
                "Reddit ExperiencedDevs",
                "https://www.reddit.com/r/ExperiencedDevs/.rss",
                "rss",
                8,
                8000,
                true,
                "Replaced new.json (403) with Atom feed. Reddit rate-limits bursts; keep delay high.",
                config("http_first", 48, List.of("reddit.com"), false, true)
        ));

        seeds.add(seed(
                "geeksforgeeks-interviews",
                "GeeksforGeeks Interview Experiences",
                "https://www.geeksforgeeks.org/category/experiences/interview-experiences/",
                "html",
                12,
                4000,
                true,
                "HTTP 200 listing with live /interview-experiences/{slug} articles. GFG RSS 404.",
                config("http_first", 48, List.of("geeksforgeeks.org"), false, false)
        ));

        seeds.add(seed(
                "devto-interview",
                "DEV Community Interview",
                "https://dev.to/t/interview",
                "html",
                12,
                3000,
                true,
                "Canonical tag URL is /t/interview (old /tag/interview redirects). HTTP 200.",
                config("http_first", 48, List.of("dev.to"), false, false)
        ));

        seeds.add(seed(
                "devto-interview-feed",
                "DEV Community Interview RSS",
                "https://dev.to/feed/tag/interview",
                "rss",
                12,
                3000,
                true,
                "HTTP 200 RSS with full article bodies and pubDate — cheaper than HTML discovery.",
                config("http_first", 48, List.of("dev.to"), false, true)
        ));

        seeds.add(seed(
                "devto-interview-experience",
                "DEV Community Interview Experience RSS",
                "https://dev.to/feed/tag/interviewexperience",
                "rss",
                12,
                3000,
                true,
                "HTTP 200 RSS of candidate write-ups. Lower volume, higher signal than #interview.",
                config("http_first", 72, List.of("dev.to"), false, true)
        ));

        seeds.add(seed(
                "medium-software-interviews",
                "Medium Coding Interview RSS",
                "https://medium.com/feed/tag/coding-interview",
                "rss",
                10,
                5000,
                true,
                "HTML tag page is Cloudflare 403. coding-interview RSS is 200 with dated items. "
                        + "Generic /tag/interview RSS is noisy (non-SWE content).",
                config("http_first", 48, List.of("medium.com"), false, true)
        ));

        seeds.add(seed(
                "hashnode-interview",
                "Hashnode Interview",
                "https://hashnode.com/tag/interview",
                "html",
                10,
                4000,
                true,
                "HTTP 200 tag page but article links are JS-rendered; Chromium first. "
                        + "hashnode.com/n/interview redirects here. Public tag RSS 404.",
                config("browser_first", 48, List.of("hashnode.com", "hashnode.dev"), true, false)
        ));

        // Retired: verified as the wrong kind of page, not a broken crawl of good sources.
        seeds.add(retired(
                "interviewbit-questions",
                "InterviewBit Coding Interview Questions",
                "https://www.interviewbit.com/coding-interview-questions/",
                "html",
                "HTTP 200 but this is a practice-problem catalog, not interview experiences."
        ));
        seeds.add(retired(
                "codeforces-problems",
                "Codeforces Problemset",
                "https://codeforces.com/problemset",
                "html",
                "Cloudflare 403 and not interview content (contest problemset)."
        ));
        seeds.add(retired(
                "stackoverflow-interview-questions",
                "Stack Overflow Interview Questions",
                "https://stackoverflow.com/questions/tagged/interview-questions",
                "html",
                "Cloudflare 403; tag is mostly meta Q&A, not company interview write-ups. RSS also 403."
        ));
        seeds.add(retired(
                "hackerrank-interview-prep",
                "HackerRank Interview Preparation Kit",
                "https://www.hackerrank.com/interview/interview-preparation-kit",
                "html",
                "HTTP 200 but this is a prep kit / skill track, not recent interview experiences."
        ));

        return List.copyOf(seeds);
    }

    public static List<Seed> enabled() {
        return all().stream().filter(Seed::enabled).toList();
    }

    private static Seed retired(String slug, String name, String url, String kind, String notes) {
        return seed(slug, name, url, kind, 4, 8000, false, notes,
                config("http_first", 48, List.of(), false, false));
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

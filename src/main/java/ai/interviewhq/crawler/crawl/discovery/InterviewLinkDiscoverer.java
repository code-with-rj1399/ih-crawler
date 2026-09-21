package ai.interviewhq.crawler.crawl.discovery;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cheap, deterministic URL discovery. No model calls — the crawler owns this step.
 */
public final class InterviewLinkDiscoverer {

    public record DiscoveredLink(String url, String title, int score) {
        public String anchorText() {
            return title;
        }
    }

    private static final Pattern INTERVIEW_TOKEN = Pattern.compile(
            "interview|experience|onsite|phone.?screen|system.?design|coding.?question|"
                    + "asked me|leetcode|hiring|recruiter|online.?assessment|\\boa\\b|"
                    + "virtual.?onsite|bar.?raiser|loop",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern EXCLUDE = Pattern.compile(
            "(login|log-in|signin|sign-in|signup|sign-up|register|checkout|cart|privacy|"
                    + "terms|cookie|account|settings|logout|password|wp-admin|wp-login|"
                    + "mailto:|javascript:|tel:)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ASSET = Pattern.compile(
            "\\.(png|jpe?g|gif|svg|webp|css|js|pdf|zip|mp4|mp3|woff2?|ico)(\\?|$)",
            Pattern.CASE_INSENSITIVE);

    public List<DiscoveredLink> discover(String pageUrl, String html, int max) {
        if (html == null || html.isBlank() || pageUrl == null || pageUrl.isBlank()) {
            return List.of();
        }

        Document doc = Jsoup.parse(html, pageUrl);
        String host = hostOf(pageUrl);
        Map<String, DiscoveredLink> ranked = new LinkedHashMap<>();

        Elements links = doc.select("a[href]");
        for (Element link : links) {
            String href = link.absUrl("href");
            if (href.isBlank()) {
                continue;
            }
            href = stripFragment(href);
            if (href.equals(stripFragment(pageUrl))) {
                continue;
            }
            if (!href.startsWith("http://") && !href.startsWith("https://")) {
                continue;
            }
            if (ASSET.matcher(href).find() || EXCLUDE.matcher(href).find()) {
                continue;
            }

            String title = link.text() == null ? "" : link.text().trim();
            String haystack = (href + " " + title).toLowerCase(Locale.ROOT);
            if (EXCLUDE.matcher(haystack).find() && !INTERVIEW_TOKEN.matcher(haystack).find()) {
                continue;
            }

            String linkHost = hostOf(href);
            boolean sameHost = host != null && host.equalsIgnoreCase(linkHost);

            // LeetCode Discuss currently exposes the actual interview posts under
            // /discuss/post/. The /discuss/ page also contains global navigation
            // links (Problems, Contest, Create, homepage, footer). Do not let the
            // generic interview scoring promote those links to article candidates.
            if ("leetcode.com".equals(host) && !isLeetcodeInterviewPost(href)) {
                continue;
            }
            boolean subdomain = host != null && linkHost != null
                    && (linkHost.endsWith("." + host) || host.endsWith("." + linkHost));
            if (!sameHost && !subdomain) {
                continue;
            }

            int score = score(href, title, sameHost);
            if (score < 2) {
                continue;
            }

            DiscoveredLink existing = ranked.get(href);
            if (existing == null || score > existing.score()) {
                ranked.put(href, new DiscoveredLink(href, title.isBlank() ? href : title, score));
            }
        }

        List<DiscoveredLink> out = new ArrayList<>(ranked.values());
        out.sort(Comparator.comparingInt(DiscoveredLink::score).reversed());
        if (out.size() > max) {
            return List.copyOf(out.subList(0, max));
        }
        return List.copyOf(out);
    }

    public List<String> paginationUrls(String pageUrl, String html) {
        if (html == null || html.isBlank() || pageUrl == null) {
            return List.of();
        }
        Document doc = Jsoup.parse(html, pageUrl);
        String host = hostOf(pageUrl);
        List<String> pages = new ArrayList<>();
        for (Element el : doc.select("a[rel=next], link[rel=next], a[href*=page=], a[href*=/page/], a[href*=?p=]")) {
            String href = el.absUrl("href");
            if (href.isBlank() || !href.startsWith("http")) {
                continue;
            }
            href = stripFragment(href);
            if (host != null && !host.equalsIgnoreCase(hostOf(href))) {
                continue;
            }
            if (href.equals(stripFragment(pageUrl))) {
                continue;
            }
            if (!pages.contains(href)) {
                pages.add(href);
            }
            if (pages.size() >= 6) {
                break;
            }
        }
        for (Element el : doc.select("a")) {
            String text = el.text() == null ? "" : el.text().trim().toLowerCase(Locale.ROOT);
            if (!text.equals("next") && !text.equals("older") && !text.equals("next page") && !text.equals(">")) {
                continue;
            }
            String href = stripFragment(el.absUrl("href"));
            if (href.startsWith("http") && (host == null || host.equalsIgnoreCase(hostOf(href)))
                    && !pages.contains(href) && !href.equals(stripFragment(pageUrl))) {
                pages.add(href);
            }
            if (pages.size() >= 6) {
                break;
            }
        }
        return List.copyOf(pages);
    }

    private static boolean isLeetcodeInterviewPost(String url) {
        String path = URI.create(url).getPath();
        if (path == null) {
            return false;
        }
        return path.matches("/discuss/post/[^/]+/?")
                || path.matches("/discuss/interview-experience/[^/]+/?");
    }

    static int score(String href, String title, boolean sameHost) {
        String url = href.toLowerCase(Locale.ROOT);
        String text = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int score = sameHost ? 3 : 1;

        if (INTERVIEW_TOKEN.matcher(url).find()) {
            score += 5;
        }
        if (INTERVIEW_TOKEN.matcher(text).find()) {
            score += 4;
        }
        if (url.contains("/discuss/") || url.contains("/post/") || url.contains("/blog/")
                || url.contains("/questions/") || url.contains("/experience")) {
            score += 3;
        }
        if (url.contains("/tag/") || url.contains("/category/") || url.contains("/topics/")
                || url.matches(".*/discuss/(interview-experience|interview-questions)/?$")) {
            score -= 8;
        }
        if (slugLooksLikeArticle(url)) {
            score += 2;
        }
        if (text.length() >= 24) {
            score += 1;
        }
        return score;
    }

    private static boolean slugLooksLikeArticle(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.length() < 12) {
                return false;
            }
            String last = path.substring(path.lastIndexOf('/') + 1);
            return last.length() >= 16 && last.contains("-");
        } catch (Exception e) {
            return false;
        }
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return null;
        }
    }

    private static String stripFragment(String url) {
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }
}

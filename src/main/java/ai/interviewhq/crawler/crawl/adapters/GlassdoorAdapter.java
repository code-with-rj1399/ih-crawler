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
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class GlassdoorAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(GlassdoorAdapter.class);

    @Override
    public String kind() {
        return "glassdoor";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception {
        FetchResult result;
        try {
            result = fetcher.get(source, source.getUrl());
        } catch (Exception ex) {
            log.warn("glassdoor blocked/fail-closed for {}: {}", source.getSlug(), ex.getMessage());
            throw ex;
        }
        if (!result.robotsAllowed()) {
            log.info("glassdoor robots disallow {}", source.getUrl());
            return List.of();
        }
        if (!result.isSuccess()) {
            log.info("glassdoor HTTP {} for {} — fail closed", result.status(), source.getSlug());
            return List.of();
        }
        String company = ParserConfigs.text(source, "company", null);
        Document doc = Jsoup.parse(result.bodyAsString(), source.getUrl());
        List<ParsedEntry> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        Elements snippets = doc.select(
                "[data-test*=nterview] p, [data-test*=nterview] li, .interviewQuestion, "
                        + "[class*=InterviewQuestion], [class*=interviewQuestion], "
                        + "li.empReview, .interview-details, blockquote, article p"
        );
        for (Element el : snippets) {
            String text = el.text();
            if (text == null || text.length() < 40) {
                continue;
            }
            if (!looksLikeInterview(text)) {
                continue;
            }
            String hash = Hashing.sha256Hex(text);
            if (!seen.add(hash)) {
                continue;
            }
            String url = source.getUrl() + "#snippet-" + hash.substring(0, 12);
            entries.add(ParsedEntry.builder(url)
                    .canonicalUrl(source.getUrl())
                    .title(truncate(text, 180))
                    .rawCompany(company)
                    .publishedAt(null)
                    .bodyText(text)
                    .contentType(result.contentType())
                    .httpStatus(result.status())
                    .etag(result.etag())
                    .contentHash(hash)
                    .robotsAllowed(true)
                    .build());
            if (entries.size() >= 20) {
                break;
            }
        }
        if (entries.isEmpty()) {
            log.info("glassdoor HTML contained no interview snippets for {} (bot wall or empty listing)", source.getSlug());
        }
        return entries;
    }

    private static boolean looksLikeInterview(String text) {
        String lower = text.toLowerCase();
        return lower.contains("interview")
                || lower.contains("asked me")
                || lower.contains("coding")
                || lower.contains("system design")
                || lower.contains("leetcode")
                || lower.contains("onsite")
                || lower.contains("phone screen");
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}

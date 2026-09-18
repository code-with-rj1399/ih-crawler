package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.crawl.SourceAdapter;
import ai.interviewhq.crawler.crawl.http.FetchResult;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class HnAlgoliaAdapter implements SourceAdapter {

    private final ObjectMapper mapper;

    public HnAlgoliaAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "hn_algolia";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception {
        String url = withNumericFilter(source.getUrl(), lookback);
        FetchResult result = fetcher.get(source, url);
        if (!result.robotsAllowed() || result.isNotModified()) {
            return List.of();
        }
        if (!result.isSuccess()) {
            throw new IllegalStateException("HN Algolia HTTP " + result.status());
        }
        JsonNode hits = mapper.readTree(result.bodyAsString()).path("hits");
        if (!hits.isArray()) {
            return List.of();
        }
        List<ParsedEntry> entries = new ArrayList<>();
        for (JsonNode hit : hits) {
            long created = hit.path("created_at_i").asLong(0);
            Instant posted = created > 0 ? Instant.ofEpochSecond(created) : parseIso(hit.path("created_at").asText(null));
            if (posted != null && posted.isBefore(lookback)) {
                continue;
            }
            String objectId = hit.path("objectID").asText(null);
            String storyUrl = textOrNull(hit, "url");
            String hnUrl = objectId == null ? storyUrl : "https://news.ycombinator.com/item?id=" + objectId;
            String canonical = storyUrl != null ? storyUrl : hnUrl;
            if (canonical == null) {
                continue;
            }
            String title = textOrNull(hit, "title");
            String author = textOrNull(hit, "author");
            String storyText = firstNonBlank(textOrNull(hit, "story_text"), textOrNull(hit, "comment_text"), title);
            entries.add(ParsedEntry.builder(hnUrl == null ? canonical : hnUrl)
                    .canonicalUrl(canonical)
                    .externalId(objectId)
                    .title(title)
                    .author(author)
                    .publishedAt(posted)
                    .bodyText(storyText)
                    .contentType(result.contentType())
                    .httpStatus(result.status())
                    .etag(result.etag())
                    .contentHash(Hashing.sha256Hex(storyText == null ? canonical : storyText))
                    .robotsAllowed(true)
                    .build());
        }
        return entries;
    }

    static String withNumericFilter(String url, Instant lookback) {
        long epoch = lookback.getEpochSecond();
        String filter = "numericFilters=created_at_i>" + epoch;
        if (url.contains("numericFilters=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + filter;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String text = v.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static Instant parseIso(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

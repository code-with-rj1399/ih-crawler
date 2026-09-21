package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.crawl.ArticleBodyEnricher;
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
public class RedditJsonAdapter implements SourceAdapter {

    private static final int ENRICH_CAP = 8;

    private final ObjectMapper mapper;
    private final ArticleBodyEnricher enricher;

    public RedditJsonAdapter(ObjectMapper mapper, ArticleBodyEnricher enricher) {
        this.mapper = mapper;
        this.enricher = enricher;
    }

    @Override
    public String kind() {
        return "reddit_json";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception {
        FetchResult result = fetcher.get(source, source.getUrl());
        if (!result.robotsAllowed() || result.isNotModified()) {
            return List.of();
        }
        if (!result.isSuccess()) {
            throw new IllegalStateException("reddit JSON HTTP " + result.status());
        }
        JsonNode root = mapper.readTree(result.bodyAsString());
        JsonNode children = root.path("data").path("children");
        if (!children.isArray()) {
            return List.of();
        }
        long lookbackEpoch = lookback.getEpochSecond();
        List<ParsedEntry> entries = new ArrayList<>();
        for (JsonNode child : children) {
            JsonNode data = child.path("data");
            if (data.isMissingNode()) {
                continue;
            }
            long created = data.path("created_utc").asLong(0);
            if (created > 0 && created < lookbackEpoch) {
                continue;
            }
            String permalink = data.path("permalink").asText("");
            String url = permalink.startsWith("http")
                    ? permalink
                    : (permalink.isBlank() ? data.path("url").asText(null) : "https://www.reddit.com" + permalink);
            if (url == null || url.isBlank()) {
                continue;
            }
            String title = data.path("title").asText(null);
            String author = data.path("author").asText(null);
            String selftext = data.path("selftext").asText("");
            String body = (title == null ? "" : title) + (selftext.isBlank() ? "" : "\n\n" + selftext);
            Instant posted = created > 0 ? Instant.ofEpochSecond(created) : null;
            entries.add(ParsedEntry.builder(url)
                    .canonicalUrl(url)
                    .externalId(data.path("id").asText(null))
                    .title(title)
                    .author(author == null || author.equals("[deleted]") ? null : author)
                    .publishedAt(posted)
                    .bodyText(body)
                    .contentType(result.contentType())
                    .httpStatus(result.status())
                    .etag(result.etag())
                    .contentHash(Hashing.sha256Hex(body))
                    .robotsAllowed(true)
                    .build());
        }
        List<ParsedEntry> enriched = new ArrayList<>(entries.size());
        int fetched = 0;
        for (ParsedEntry entry : entries) {
            if (fetched < ENRICH_CAP) {
                ParsedEntry next = enricher.enrich(source, entry);
                if (next != entry) {
                    fetched++;
                }
                enriched.add(next);
            } else {
                enriched.add(entry);
            }
        }
        return enriched;
    }
}

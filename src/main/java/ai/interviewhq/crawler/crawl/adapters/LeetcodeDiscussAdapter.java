package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.crawl.SourceAdapter;
import ai.interviewhq.crawler.crawl.http.FetchResult;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class LeetcodeDiscussAdapter implements SourceAdapter {

    private static final Logger log = LoggerFactory.getLogger(LeetcodeDiscussAdapter.class);
    private static final String GRAPHQL = "https://leetcode.com/graphql/";

    private static final String LIST_QUERY = """
            query list($keywords: [String]!, $tagSlugs: [String!], $skip: Int, $first: Int) {
              ugcArticleDiscussionArticles(
                keywords: $keywords
                tagSlugs: $tagSlugs
                skip: $skip
                first: $first
                orderBy: MOST_RECENT
              ) {
                edges {
                  node {
                    uuid
                    title
                    slug
                    summary
                    createdAt
                    updatedAt
                    hitCount
                    topicId
                    tags { name slug }
                    author { userName }
                  }
                }
              }
            }
            """;

    private static final String BODY_QUERY = """
            query body($topicId: ID) {
              ugcArticleDiscussionArticle(topicId: $topicId) {
                uuid
                title
                content
                createdAt
              }
            }
            """;

    private final ObjectMapper mapper;

    public LeetcodeDiscussAdapter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String kind() {
        return "leetcode_discuss";
    }

    @Override
    public List<ParsedEntry> crawl(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception {
        return crawlGraphql(source, lookback, fetcher);
    }

    private List<ParsedEntry> crawlGraphql(CrawlSource source, Instant lookback, PoliteFetcher fetcher)
            throws Exception {

        List<ParsedEntry> entries = new ArrayList<>();
        int skip = 0;
        int pageSize = 20;

        while (entries.size() < 50) {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("query", LIST_QUERY);

            ObjectNode variables = payload.putObject("variables");
            ArrayNode keywords = variables.putArray("keywords");
            keywords.add("interview");
            variables.putArray("tagSlugs");
            variables.put("skip", skip);
            variables.put("first", pageSize);

            FetchResult listResult = fetcher.postJson(
                    source,
                    GRAPHQL,
                    mapper.writeValueAsString(payload)
            );

            ensureGraphqlSuccess(listResult, "discussion list");

            JsonNode root = mapper.readTree(listResult.bodyAsString());
            JsonNode edges = root.path("data")
                    .path("ugcArticleDiscussionArticles")
                    .path("edges");

            if (!edges.isArray() || edges.isEmpty()) {
                break;
            }

            int pageCount = 0;

            for (JsonNode edge : edges) {
                JsonNode node = edge.path("node");

                Instant posted = epochOrIso(node.path("createdAt"));
                if (posted != null && posted.isBefore(lookback)) {
                    return entries;
                }

                String topicId = node.path("topicId").asText(null);
                String uuid = node.path("uuid").asText(null);
                String title = node.path("title").asText(null);
                String summary = node.path("summary").asText("");
                String author = node.path("author").path("userName").asText(null);
                String slug = node.path("slug").asText(null);

                String body = fetchBody(topicId, fetcher, source);
                if (body == null || body.isBlank()) {
                    body = summary;
                }

                String url = topicId == null
                        ? source.getUrl()
                        : "https://leetcode.com/discuss/post/" + topicId + "/" + (slug == null ? "" : slug) + "/";

                entries.add(ParsedEntry.builder(url)
                        .canonicalUrl(url)
                        .externalId(uuid != null ? uuid : topicId)
                        .title(title)
                        .author(author)
                        .publishedAt(posted)
                        .bodyText(Jsoup.parse(body == null ? "" : body).text())
                        .contentType("application/json")
                        .httpStatus(listResult.status())
                        .contentHash(Hashing.sha256Hex(body == null ? url : body))
                        .robotsAllowed(true)
                        .build());

                pageCount++;

                if (entries.size() >= 50) {
                    break;
                }
            }

            log.info("LeetCode GraphQL returned {} entries from page skip={}", pageCount, skip);

            if (pageCount < pageSize) {
                break;
            }

            skip += pageSize;
        }

        return entries;
    }

    private String fetchBody(String topicId, PoliteFetcher fetcher, CrawlSource source) throws Exception {
        if (topicId == null || topicId.isBlank()) {
            return null;
        }

        ObjectNode payload = mapper.createObjectNode();
        payload.put("query", BODY_QUERY);
        payload.putObject("variables").put("topicId", topicId);

        FetchResult result = fetcher.postJson(
                source,
                GRAPHQL,
                mapper.writeValueAsString(payload)
        );

        ensureGraphqlSuccess(result, "discussion body");

        JsonNode root = mapper.readTree(result.bodyAsString());
        return root.path("data")
                .path("ugcArticleDiscussionArticle")
                .path("content")
                .asText("");
    }

    private void ensureGraphqlSuccess(FetchResult result, String operation) {
        if (!result.robotsAllowed()) {
            throw new IllegalStateException("LeetCode GraphQL " + operation + " blocked by robots policy");
        }
        if (!result.isSuccess()) {
            throw new IllegalStateException("LeetCode GraphQL " + operation + " returned HTTP " + result.status());
        }
    }

    private static Instant epochOrIso(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }

        if (node.isNumber()) {
            long value = node.asLong();
            if (value > 10_000_000_000L) {
                return Instant.ofEpochMilli(value);
            }
            if (value > 0) {
                return Instant.ofEpochSecond(value);
            }
        }

        String text = node.asText();
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            if (text.chars().allMatch(Character::isDigit)) {
                long value = Long.parseLong(text);
                return value > 10_000_000_000L
                        ? Instant.ofEpochMilli(value)
                        : Instant.ofEpochSecond(value);
            }

            if (text.endsWith("Z") || text.contains("+")) {
                return Instant.parse(text);
            }

            return OffsetDateTime.parse(text).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}

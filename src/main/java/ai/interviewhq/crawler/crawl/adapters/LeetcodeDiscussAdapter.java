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
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
    private static final String GRAPHQL = "https://leetcode.com/graphql";

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
        String category = ParserConfigs.text(source, "category", "interview-experience");
        try {
            List<ParsedEntry> fromGraphql = crawlGraphql(source, lookback, fetcher, category);
            if (!fromGraphql.isEmpty()) {
                return fromGraphql;
            }
        } catch (Exception ex) {
            log.info("leetcode GraphQL failed for {} ({}): falling back to HTML", source.getSlug(), ex.getMessage());
        }
        return crawlHtml(source, lookback, fetcher);
    }

    private List<ParsedEntry> crawlGraphql(CrawlSource source, Instant lookback, PoliteFetcher fetcher, String category)
            throws Exception {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("operationName", "categoryTopicList");
        payload.put("query", """
                query categoryTopicList($categories: [String!]!, $first: Int, $orderBy: TopicSortingOption, $skip: Int, $query: String, $tags: [String!]) {
                  categoryTopicList(categories: $categories, first: $first, orderBy: $orderBy, skip: $skip, query: $query, tags: $tags) {
                    edges {
                      node {
                        id
                        title
                        commentCount
                        viewCount
                        creationDate
                        post {
                          id
                          content
                          creationDate
                          author { username }
                        }
                      }
                    }
                  }
                }
                """);
        ObjectNode variables = payload.putObject("variables");
        ArrayNode categories = variables.putArray("categories");
        categories.add(category);
        variables.put("first", 20);
        variables.put("orderBy", "newest_to_oldest");
        variables.put("skip", 0);
        variables.put("query", "");
        variables.putArray("tags");

        FetchResult result = fetcher.postJson(source, GRAPHQL, mapper.writeValueAsString(payload));
        if (!result.robotsAllowed() || !result.isSuccess()) {
            return List.of();
        }
        JsonNode root = mapper.readTree(result.bodyAsString());
        if (root.has("errors")) {
            log.info("leetcode GraphQL errors: {}", root.get("errors"));
            return List.of();
        }
        JsonNode edges = root.path("data").path("categoryTopicList").path("edges");
        if (!edges.isArray() || edges.isEmpty()) {
            return List.of();
        }
        List<ParsedEntry> entries = new ArrayList<>();
        for (JsonNode edge : edges) {
            JsonNode node = edge.path("node");
            Instant posted = epochOrIso(node.path("creationDate"));
            if (posted == null) {
                posted = epochOrIso(node.path("post").path("creationDate"));
            }
            if (posted != null && posted.isBefore(lookback)) {
                continue;
            }
            String id = node.path("id").asText(null);
            String title = node.path("title").asText(null);
            String author = node.path("post").path("author").path("username").asText(null);
            String contentHtml = node.path("post").path("content").asText("");
            String body = contentHtml.isBlank() ? title : Jsoup.parse(contentHtml).text();
            String url = id == null
                    ? source.getUrl()
                    : "https://leetcode.com/discuss/topic/" + id;
            entries.add(ParsedEntry.builder(url)
                    .canonicalUrl(url)
                    .externalId(id)
                    .title(title)
                    .author(author)
                    .publishedAt(posted)
                    .bodyText(body)
                    .contentType("application/json")
                    .httpStatus(result.status())
                    .contentHash(Hashing.sha256Hex(body == null ? url : body))
                    .robotsAllowed(true)
                    .build());
        }
        return entries;
    }

    private List<ParsedEntry> crawlHtml(CrawlSource source, Instant lookback, PoliteFetcher fetcher) throws Exception {
        FetchResult result = fetcher.get(source, source.getUrl());
        if (!result.robotsAllowed() || result.isNotModified()) {
            return List.of();
        }
        if (!result.isSuccess()) {
            log.info("leetcode HTML listing HTTP {} — fail closed", result.status());
            return List.of();
        }
        Document doc = Jsoup.parse(result.bodyAsString(), source.getUrl());
        List<ParsedEntry> entries = new ArrayList<>();
        for (Element link : doc.select("a[href*=/discuss/]")) {
            String href = link.absUrl("href");
            String title = link.text();
            if (href.isBlank() || title.isBlank() || href.contains("/discuss/interview-experience") && href.equals(source.getUrl())) {
                continue;
            }
            if (!href.contains("/discuss/")) {
                continue;
            }
            entries.add(ParsedEntry.builder(href)
                    .canonicalUrl(href)
                    .title(title)
                    .publishedAt(null)
                    .bodyText(title)
                    .contentType(result.contentType())
                    .httpStatus(result.status())
                    .contentHash(Hashing.sha256Hex(href + title))
                    .robotsAllowed(true)
                    .build());
            if (entries.size() >= 25) {
                break;
            }
        }
        return entries;
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
                return value > 10_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
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

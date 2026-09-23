package ai.interviewhq.crawler.crawl.leetcode;

import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.domain.CrawlSource;
import ai.interviewhq.crawler.util.Hashing;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * LeetCode-only GraphQL reader.
 *
 * Uses the current Discuss GraphQL operation used by the public
 * leetcode-interview-questions ingestion project:
 * ugcArticleDiscussionArticles(orderBy, keywords, tagSlugs, skip, first)
 * followed by ugcArticleDiscussionArticle(topicId) for full content.
 */
@Component
public class LeetcodeGraphqlClient {

    private static final Logger log = LoggerFactory.getLogger(LeetcodeGraphqlClient.class);
    private static final URI API = URI.create("https://leetcode.com/graphql/");

    private static final String LIST_QUERY = """
            query discussPostItems($orderBy: ArticleOrderByEnum, $keywords: [String]!, $tagSlugs: [String!], $skip: Int, $first: Int) {
              ugcArticleDiscussionArticles(
                orderBy: $orderBy
                keywords: $keywords
                tagSlugs: $tagSlugs
                skip: $skip
                first: $first
              ) {
                totalNum
                pageInfo { hasNextPage }
                edges {
                  node {
                    uuid
                    title
                    slug
                    summary
                    author {
                      realName
                      userSlug
                      userName
                    }
                    isAnonymous
                    createdAt
                    updatedAt
                    status
                    topicId
                    tags { name slug tagType }
                  }
                }
              }
            }
            """;

    private static final String DETAIL_QUERY = """
            query discussPostDetail($topicId: ID!) {
              ugcArticleDiscussionArticle(topicId: $topicId) {
                uuid
                title
                slug
                summary
                content
                author {
                  realName
                  userSlug
                  userName
                }
                isAnonymous
                createdAt
                updatedAt
                status
                topicId
                tags { name slug tagType }
              }
            }
            """;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public LeetcodeGraphqlClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public List<ParsedEntry> fetchRecent(CrawlSource source, Instant cutoff, int maxPosts) {
        List<ParsedEntry> entries = new ArrayList<>();
        fetchRecentStreaming(source, cutoff, maxPosts, entries::add);
        return entries;
    }

    /**
     * Processes LeetCode posts strictly sequentially. A post is not emitted
     * until its listing metadata, detail request, freshness checks and
     * content construction have all completed.
     */
    public void fetchRecentStreaming(
            CrawlSource source,
            Instant cutoff,
            int maxPosts,
            Consumer<ParsedEntry> consumer) {
        int target = Math.max(1, Math.min(100, maxPosts));
        int pageSize = Math.min(50, target);
        int delayMs = source == null ? 4000 : Math.max(0, source.getCrawlDelayMs());
        int emitted = 0;

        try {
            int skip = 0;
            boolean hasNextPage = true;

            while (emitted < target && hasNextPage) {
                int requested = Math.min(pageSize, target - emitted);
                JsonNode listing = execute(LIST_QUERY, Map.of(
                        "orderBy", "MOST_RECENT",
                        "keywords", List.of(),
                        "tagSlugs", List.of("interview"),
                        "skip", skip,
                        "first", requested
                ));

                JsonNode container = listing.path("data")
                        .path("ugcArticleDiscussionArticles");
                JsonNode edges = container.path("edges");

                if (!edges.isArray() || edges.isEmpty()) {
                    log.warn("LeetCode GraphQL returned no discussion edges: skip={}", skip);
                    break;
                }

                hasNextPage = container.path("pageInfo").path("hasNextPage").asBoolean(false);

                for (JsonNode edge : edges) {
                    if (emitted >= target) {
                        break;
                    }

                    JsonNode node = edge.path("node");
                    if (node.isMissingNode()) {
                        continue;
                    }

                    Instant createdAt = parseInstant(node.path("createdAt").asText(null));
                    if (createdAt == null) {
                        log.info("LeetCode GraphQL skipping undated post topicId={}",
                                node.path("topicId").asText(""));
                        continue;
                    }
                    if (cutoff != null && createdAt.isBefore(cutoff)) {
                        log.info("Rejecting LeetCode post due to freshness: topicId={} title={} createdAt={} cutoff={}",
                                node.path("topicId").asText(""), node.path("title").asText(""), createdAt, cutoff);
                        hasNextPage = false;
                        break;
                    }

                    String topicId = node.path("topicId").asText(null);
                    if (topicId == null || topicId.isBlank()) {
                        continue;
                    }

                    // Per-source sequential pacing: finish this post before
                    // starting the next post.
                    sleep(delayMs);

                    JsonNode detail = execute(DETAIL_QUERY, Map.of("topicId", topicId))
                            .path("data")
                            .path("ugcArticleDiscussionArticle");

                    if (detail.isMissingNode() || detail.isNull()) {
                        log.info("LeetCode GraphQL detail missing topicId={}", topicId);
                        continue;
                    }

                    String title = text(detail, "title", node.path("title").asText(""));
                    String body = text(detail, "content", node.path("summary").asText(""));
                    if (body == null || body.isBlank()) {
                        body = node.path("summary").asText("");
                    }
                    if (body == null || body.isBlank()) {
                        continue;
                    }

                    String slug = text(detail, "slug", node.path("slug").asText(""));
                    String url = "https://leetcode.com/discuss/post/" + topicId + "/";
                    if (slug != null && !slug.isBlank()) {
                        url = "https://leetcode.com/discuss/post/" + topicId + "/" + slug + "/";
                    }

                    String author = author(detail.path("author"));
                    if (author == null || author.isBlank()) {
                        author = author(node.path("author"));
                    }

                    ParsedEntry entry = ParsedEntry.builder(url)
                            .canonicalUrl(url)
                            .externalId(topicId)
                            .title(title)
                            .author(Boolean.TRUE.equals(detail.path("isAnonymous").asBoolean(false))
                                    ? "Anonymous" : author)
                            .publishedAt(createdAt)
                            .bodyText(body)
                            .contentType("text/html")
                            .httpStatus(200)
                            .contentHash(Hashing.sha256Hex(body))
                            .robotsAllowed(Boolean.TRUE)
                            .build();

                    log.info("LeetCode GraphQL post ready: topicId={} createdAt={} title={}",
                            topicId, createdAt, title);

                    // The callback performs AI extraction + dedupe + persistence
                    // before this source proceeds to the next post.
                    consumer.accept(entry);
                    emitted++;
                }

                skip += edges.size();
                if (edges.size() < requested) {
                    hasNextPage = false;
                }
            }

            log.info("LeetCode GraphQL crawl finished: processed={} target={}", emitted, target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("LeetCode GraphQL crawl interrupted", e);
        } catch (Exception e) {
            log.warn("LeetCode GraphQL crawl failed: {}", e.getMessage());
        }
    }

    private JsonNode execute(String query, Map<String, Object> variables) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("operationName", operationName(query));
        payload.put("query", query);
        payload.put("variables", variables);

        String json = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder(API)
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Origin", "https://leetcode.com")
                .header("Referer", "https://leetcode.com/discuss/topic/interview/")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = httpClient.send(
                request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "HTTP " + response.statusCode() + " from LeetCode GraphQL");
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (root.has("errors") && root.path("errors").size() > 0) {
            throw new IllegalStateException("LeetCode GraphQL errors: " + root.path("errors"));
        }
        return root;
    }

    private static String operationName(String query) {
        int start = query.indexOf("query ");
        if (start < 0) {
            return "";
        }
        int nameStart = start + "query ".length();
        int end = query.indexOf('(', nameStart);
        if (end < 0) {
            end = query.indexOf(' ', nameStart);
        }
        return end > nameStart ? query.substring(nameStart, end).trim() : "";
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText(null);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String author(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String realName = node.path("realName").asText(null);
        if (realName != null && !realName.isBlank()) {
            return realName;
        }
        String userName = node.path("userName").asText(null);
        return userName == null || userName.isBlank() ? null : userName;
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void sleep(int delayMs) throws InterruptedException {
        if (delayMs > 0) {
            Thread.sleep(delayMs);
        }
    }
}

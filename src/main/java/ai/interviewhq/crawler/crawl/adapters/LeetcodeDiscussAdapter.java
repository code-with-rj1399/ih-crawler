package ai.interviewhq.crawler.crawl.adapters;

import ai.interviewhq.crawler.crawl.ParsedEntry;
import ai.interviewhq.crawler.crawl.SourceAdapter;
import ai.interviewhq.crawler.crawl.http.PoliteFetcher;
import ai.interviewhq.crawler.crawl.leetcode.LeetcodeGraphqlClient;
import ai.interviewhq.crawler.domain.CrawlSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

@Component
public class LeetcodeDiscussAdapter implements SourceAdapter {

    private final LeetcodeGraphqlClient client;

    public LeetcodeDiscussAdapter(LeetcodeGraphqlClient client) {
        this.client = client;
    }

    @Override
    public String kind() {
        return "leetcode_graphql";
    }

    @Override
    public void crawlStreaming(
            CrawlSource source,
            Instant lookback,
            PoliteFetcher fetcher,
            Consumer<ParsedEntry> consumer) {
        client.fetchRecentStreaming(source, lookback, 100, consumer);
    }

    @Override
    public List<ParsedEntry> crawl(
            CrawlSource source,
            Instant lookback,
            PoliteFetcher fetcher) {
        return client.fetchRecent(source, lookback, 100);
    }
}

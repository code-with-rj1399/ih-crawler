package ai.interviewhq.crawler.crawl.http;

import ai.interviewhq.crawler.domain.CrawlSource;

public interface PoliteFetcher {

    FetchResult get(CrawlSource source, String url) throws InterruptedException;

    FetchResult get(CrawlSource source, String url, String etag) throws InterruptedException;

    FetchResult postJson(CrawlSource source, String url, String jsonBody) throws InterruptedException;
}

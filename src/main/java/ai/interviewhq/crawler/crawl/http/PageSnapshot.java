package ai.interviewhq.crawler.crawl.http;

import ai.interviewhq.crawler.browser.ChromiumBrowserClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public record PageSnapshot(
        String requestedUrl,
        String finalUrl,
        String title,
        String html,
        String text,
        int status,
        String contentType,
        String etag,
        boolean robotsAllowed,
        boolean usedBrowser,
        boolean challenge,
        String error
) {
    public boolean isSuccess() {
        return robotsAllowed
                && status >= 200
                && status < 400
                && !challenge
                && html != null
                && !html.isBlank();
    }

    public static PageSnapshot denied(String url, String reason) {
        return new PageSnapshot(url, url, "", "", "", 0, null, null, false, false, false, reason);
    }

    public static PageSnapshot fromHttp(FetchResult result) {
        String html = result.bodyAsString();
        String title = "";
        String text = "";
        if (html != null && !html.isBlank()) {
            Document doc = Jsoup.parse(html, result.finalUrl() == null ? "" : result.finalUrl());
            title = doc.title();
            text = doc.body() == null ? "" : doc.body().text();
        }
        boolean challenge = ChromiumBrowserClient.looksLikeChallenge(title, html, text, result.status());
        return new PageSnapshot(
                result.requestedUrl(),
                result.finalUrl(),
                title,
                html,
                text,
                result.status(),
                result.contentType(),
                result.etag(),
                result.robotsAllowed(),
                false,
                challenge,
                result.error()
        );
    }

    public static PageSnapshot fromBrowser(String requestedUrl, ChromiumBrowserClient.BrowserPage page) {
        if (page == null) {
            return new PageSnapshot(requestedUrl, requestedUrl, "", "", "", 0, "text/html", null,
                    true, true, true, "empty browser page");
        }
        return new PageSnapshot(
                requestedUrl,
                page.url(),
                page.title(),
                page.html(),
                page.text(),
                page.status(),
                "text/html",
                null,
                true,
                true,
                page.challenge(),
                page.challenge() ? "challenge" : null
        );
    }
}

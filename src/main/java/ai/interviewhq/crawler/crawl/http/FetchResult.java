package ai.interviewhq.crawler.crawl.http;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

public record FetchResult(
        String requestedUrl,
        String finalUrl,
        int status,
        String contentType,
        String etag,
        byte[] body,
        boolean truncated,
        boolean robotsAllowed,
        Integer robotsCrawlDelaySeconds,
        String error,
        int redirectCount,
        String retryAfter
) {
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }

    public boolean isNotModified() {
        return status == 304;
    }

    public boolean isBlocked() {
        return status == 401 || status == 403;
    }

    public boolean isRetryableStatus() {
        return status == 429 || status == 503;
    }

    public String bodyAsString() {
        if (body == null || body.length == 0) {
            return "";
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    public Optional<String> bodyOptional() {
        String text = bodyAsString();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }
}

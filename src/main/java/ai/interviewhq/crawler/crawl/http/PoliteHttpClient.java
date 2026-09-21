package ai.interviewhq.crawler.crawl.http;

import ai.interviewhq.crawler.browser.HumanDelay;
import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.crawl.CircuitOpenException;
import ai.interviewhq.crawler.crawl.FetchBlockedException;
import ai.interviewhq.crawler.crawl.SourceOutcomeService;
import ai.interviewhq.crawler.crawl.limiter.HostRateLimiter;
import ai.interviewhq.crawler.crawl.robots.RobotsRules;
import ai.interviewhq.crawler.crawl.robots.RobotsService;
import ai.interviewhq.crawler.domain.CrawlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class PoliteHttpClient implements PoliteFetcher {

    private static final Logger log = LoggerFactory.getLogger(PoliteHttpClient.class);
    private static final int MAX_REDIRECTS = 5;

    // Randomized idle time in addition to the configured crawl/robots delay.
    // This is for conservative, non-bursty crawling rather than fixed timestamp spacing.
    private static final int RANDOM_COOLING_MIN_MS = 2_000;
    private static final int RANDOM_COOLING_MAX_MS = 7_000;

    private final HttpClient httpClient;
    private final RobotsService robotsService;
    private final HostRateLimiter rateLimiter;
    private final CrawlerSettings settings;
    private final SourceOutcomeService outcomes;

    public PoliteHttpClient(
            HttpClient crawlerHttpClient,
            RobotsService robotsService,
            HostRateLimiter rateLimiter,
            CrawlerSettings settings,
            SourceOutcomeService outcomes
    ) {
        this.httpClient = crawlerHttpClient;
        this.robotsService = robotsService;
        this.rateLimiter = rateLimiter;
        this.settings = settings;
        this.outcomes = outcomes;
    }

    @Override
    public FetchResult get(CrawlSource source, String url) throws InterruptedException {
        return get(source, url, null);
    }

    @Override
    public FetchResult get(CrawlSource source, String url, String etag) throws InterruptedException {
        return execute(source, "GET", url, null, null, etag);
    }

    @Override
    public FetchResult postJson(CrawlSource source, String url, String jsonBody) throws InterruptedException {
        return execute(source, "POST", url, jsonBody, "application/json", null);
    }

    private FetchResult execute(
            CrawlSource source,
            String method,
            String url,
            String body,
            String contentType,
            String etag
    ) throws InterruptedException {
        if (source.isCircuitOpen()) {
            throw new CircuitOpenException(source.getSlug(), source.getCircuitOpenUntil());
        }

        URI uri = URI.create(url);
        RobotsRules.Decision robots = robotsService.check(source, uri);
        if (!robots.allowed()) {
            outcomes.record(source, 0, "robots.txt disallows " + uri.getPath(), false);
            return new FetchResult(url, url, 0, null, null, new byte[0], false, false,
                    robots.crawlDelaySeconds(), "robots.txt disallows " + uri.getPath(), 0, null);
        }

        int delayMs = source.getCrawlDelayMs();
        if (robots.crawlDelaySeconds() != null) {
            delayMs = Math.max(delayMs, robots.crawlDelaySeconds() * 1000);
        }

        int rpm = Math.max(1, source.getRateLimitRpm());
        int hostConc = Math.max(1, Math.min(source.getPerHostConcurrency(), settings.perHostConcurrency()));
        String host = uri.getHost();
        int maxRetries = settings.maxRetries();
        int timeoutMs = settings.timeoutMs();
        int maxBytes = settings.maxBytes();
        String userAgent = settings.userAgent();

        FetchResult last = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // Every network attempt gets an independently sampled idle interval.
            long coolingMs = randomizedCoolingMs(delayMs);
            log.info("cooling before fetch: host={}, method={}, delay={}ms", host, method, coolingMs);
            Thread.sleep(coolingMs);

            try (HostRateLimiter.Permit ignored = rateLimiter.acquire(host, rpm, delayMs, hostConc)) {
                last = sendFollowingRedirects(method, uri, body, contentType, etag, userAgent, timeoutMs, maxBytes, robots);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (Exception e) {
                log.warn("fetch error {} {} attempt {}: {}", method, url, attempt, e.getMessage());
                last = new FetchResult(url, url, 0, null, null, new byte[0], false, true,
                        robots.crawlDelaySeconds(), e.getMessage(), 0, null);
            }

            if (last != null && last.isRetryableStatus() && attempt < maxRetries) {
                long waitMs = last.status() == 403
                        ? HumanDelay.exponentialJitter(attempt, 12_000, 45_000)
                        : retryAfterMs(last.retryAfter(), attempt);
                log.info("retry {} {} status={} after {}ms (403/429 treated as human-backoff)",
                        method, url, last.status(), waitMs);
                Thread.sleep(waitMs);
                continue;
            }

            if (last != null && last.status() == 0 && last.error() != null && attempt < maxRetries) {
                Thread.sleep(backoffMs(attempt));
                continue;
            }
            break;
        }

        if (last == null) {
            last = new FetchResult(url, url, 0, null, null, new byte[0], false, true,
                    robots.crawlDelaySeconds(), "empty fetch result", 0, null);
        }

        boolean success = last.isSuccess() || last.isNotModified();
        outcomes.record(source, last.status(), last.error(), success);

        if (last.isBlocked() || last.status() == 429) {
            throw new FetchBlockedException(last.status(), last.finalUrl(),
                    "origin returned " + last.status() + " for " + last.finalUrl());
        }

        return last;
    }

    private static long randomizedCoolingMs(int configuredDelayMs) {
        int minimum = Math.max(configuredDelayMs, RANDOM_COOLING_MIN_MS);
        int maximum = Math.max(minimum + 1, RANDOM_COOLING_MAX_MS);
        return ThreadLocalRandom.current().nextLong(minimum, (long) maximum + 1);
    }

    private FetchResult sendFollowingRedirects(
            String method,
            URI start,
            String body,
            String contentType,
            String etag,
            String userAgent,
            int timeoutMs,
            int maxBytes,
            RobotsRules.Decision robots
    ) throws Exception {
        String currentMethod = method;
        URI current = start;
        String currentBody = body;
        String currentContentType = contentType;

        for (int hops = 0; hops <= MAX_REDIRECTS; hops++) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("User-Agent", userAgent)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,application/json,application/rss+xml,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cache-Control", "max-age=0")
                    .header("Upgrade-Insecure-Requests", "1");

            if (etag != null && !etag.isBlank() && "GET".equals(currentMethod)) {
                builder.header("If-None-Match", etag);
            }

            if ("POST".equals(currentMethod)) {
                builder.header("Content-Type", currentContentType == null ? "application/json" : currentContentType);
                builder.POST(HttpRequest.BodyPublishers.ofString(currentBody == null ? "" : currentBody));
            } else {
                builder.GET();
            }

            HttpResponse<InputStream> response = httpClient.send(
                    builder.build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );

            int status = response.statusCode();
            Optional<String> location = response.headers().firstValue("Location");

            if (isRedirect(status) && location.isPresent() && hops < MAX_REDIRECTS) {
                drainQuietly(response.body());
                current = current.resolve(location.get());

                if (status == 303 || ((status == 301 || status == 302) && "POST".equals(currentMethod))) {
                    currentMethod = "GET";
                    currentBody = null;
                    currentContentType = null;
                }
                continue;
            }

            byte[] bytes = readCapped(response.body(), maxBytes);
            String respContentType = response.headers().firstValue("Content-Type").orElse(null);
            String respEtag = response.headers().firstValue("ETag").orElse(null);
            String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
            String error = status >= 400 ? ("HTTP " + status) : null;

            return new FetchResult(
                    start.toString(),
                    current.toString(),
                    status,
                    respContentType,
                    respEtag,
                    bytes,
                    bytes.length == maxBytes,
                    true,
                    robots.crawlDelaySeconds(),
                    error,
                    hops,
                    retryAfter
            );
        }

        return new FetchResult(start.toString(), start.toString(), 0, null, null, new byte[0], false, true,
                robots.crawlDelaySeconds(), "too many redirects", MAX_REDIRECTS, null);
    }

    static long retryAfterMs(String retryAfter, int attempt) {
        if (retryAfter != null && !retryAfter.isBlank()) {
            String raw = retryAfter.trim();
            try {
                return Math.min(Math.max(0, Long.parseLong(raw)) * 1000L, 60_000L);
            } catch (NumberFormatException ex) {
                try {
                    ZonedDateTime when = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME);
                    long ms = Duration.between(Instant.now(), when.toInstant()).toMillis();
                    return Math.min(Math.max(ms, 0), 60_000L);
                } catch (Exception ignored) {
                    // fall through to exponential backoff
                }
            }
        }
        return backoffMs(attempt);
    }

    private static long backoffMs(int attempt) {
        long exp = (1L << Math.min(attempt, 5)) * 1000L;
        long jitter = ThreadLocalRandom.current().nextLong(0, 400);
        return Math.min(exp + jitter, 30_000L);
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws Exception {
        try (InputStream stream = in) {
            byte[] buf = stream.readNBytes(maxBytes);
            if (buf.length == maxBytes) {
                stream.skip(Long.MAX_VALUE);
            }
            return buf;
        }
    }

    private static void drainQuietly(InputStream in) {
        try (InputStream stream = in) {
            stream.skip(Long.MAX_VALUE);
        } catch (Exception ignored) {
            // ignore
        }
    }
}

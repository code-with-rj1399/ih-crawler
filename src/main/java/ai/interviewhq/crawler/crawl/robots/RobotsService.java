package ai.interviewhq.crawler.crawl.robots;

import ai.interviewhq.crawler.config.CrawlerSettings;
import ai.interviewhq.crawler.domain.CrawlSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RobotsService {

    private static final Logger log = LoggerFactory.getLogger(RobotsService.class);

    private final HttpClient httpClient;
    private final CrawlerSettings settings;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public RobotsService(HttpClient crawlerHttpClient, CrawlerSettings settings) {
        this.httpClient = crawlerHttpClient;
        this.settings = settings;
    }

    public RobotsRules.Decision check(CrawlSource source, URI uri) {
        if (ignoresRobots(source)) {
            return RobotsRules.Decision.allow(null);
        }
        String origin = originOf(uri);
        Instant now = Instant.now();
        Cached cached = cache.get(origin);
        int hours = Math.max(1, settings.defaults().getRobotsCacheHours());
        if (cached == null || cached.expiresAt.isBefore(now)) {
            cached = fetch(origin);
            cache.put(origin, cached);
        }
        String path = uri.getRawPath();
        if (path == null || path.isBlank()) {
            path = "/";
        }
        if (uri.getRawQuery() != null) {
            path = path + "?" + uri.getRawQuery();
        }
        return cached.rules.decide(settings.userAgent(), path);
    }

    public void invalidate(String origin) {
        cache.remove(origin);
    }

    private Cached fetch(String origin) {
        Duration ttl = Duration.ofHours(Math.max(1, settings.defaults().getRobotsCacheHours()));
        URI robots = URI.create(origin + "/robots.txt");
        try {
            HttpRequest request = HttpRequest.newBuilder(robots)
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", settings.userAgent())
                    .header("Accept", "text/plain,*/*;q=0.8")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status == 404 || status == 410) {
                log.info("robots.txt {} -> {} (treat as allow-all)", robots, status);
                return new Cached(RobotsRules.allowAll(), Instant.now().plus(ttl));
            }
            if (status >= 400) {
                log.warn("robots.txt {} -> {} (treat as allow-all, do not invent rules)", robots, status);
                return new Cached(RobotsRules.allowAll(), Instant.now().plus(ttl));
            }
            String body = response.body() == null ? "" : response.body();
            if (body.length() > 256_000) {
                body = body.substring(0, 256_000);
            }
            return new Cached(RobotsRules.parse(body), Instant.now().plus(ttl));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("robots.txt fetch interrupted for {}", origin);
            return new Cached(RobotsRules.allowAll(), Instant.now().plus(Duration.ofMinutes(15)));
        } catch (Exception e) {
            log.warn("robots.txt fetch failed for {}: {}", origin, e.getMessage());
            return new Cached(RobotsRules.allowAll(), Instant.now().plus(Duration.ofMinutes(15)));
        }
    }

    public static boolean ignoresRobots(CrawlSource source) {
        if (source == null || source.getRobotsMode() == null) {
            return false;
        }
        String mode = source.getRobotsMode().trim().toLowerCase();
        return mode.equals("ignore") || mode.equals("off") || mode.equals("bypass") || mode.equals("skip");
    }

    public static String originOf(URI uri) {
        String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();
        if (port > 0 && !((scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80))) {
            return scheme + "://" + host + ":" + port;
        }
        return scheme + "://" + host;
    }

    private record Cached(RobotsRules rules, Instant expiresAt) {
    }
}

package ai.interviewhq.crawler.crawl.limiter;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Per-host token bucket plus a hard in-flight cap (default 1) and a minimum gap
 * from {@code crawl_delay_ms}.
 */
@Component
public class HostRateLimiter {

    private final ConcurrentHashMap<String, HostBucket> hosts = new ConcurrentHashMap<>();

    public Permit acquire(String host, int rpm, int delayMs, int perHostConcurrency) throws InterruptedException {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is required");
        }
        String key = host.toLowerCase(Locale.ROOT);
        int concurrency = Math.max(1, perHostConcurrency);
        HostBucket bucket = hosts.computeIfAbsent(key, h -> new HostBucket(concurrency));
        if (!bucket.inFlight.tryAcquire(2, TimeUnit.MINUTES)) {
            throw new InterruptedException("timed out waiting for per-host slot on " + key);
        }
        try {
            bucket.waitTurn(Math.max(1, rpm), Math.max(0, delayMs));
        } catch (InterruptedException ex) {
            bucket.inFlight.release();
            throw ex;
        }
        return bucket::release;
    }

    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    static final class HostBucket {
        final Semaphore inFlight;
        private double tokens = 1.0;
        private long lastRefillMs = System.currentTimeMillis();
        private long lastFinishMs;

        HostBucket(int concurrency) {
            this.inFlight = new Semaphore(concurrency, true);
        }

        void waitTurn(int rpm, int delayMs) throws InterruptedException {
            double refillPerMs = rpm / 60_000.0;
            while (true) {
                long sleepMs;
                synchronized (this) {
                    long now = System.currentTimeMillis();
                    tokens = Math.min(1.0, tokens + Math.max(0, now - lastRefillMs) * refillPerMs);
                    lastRefillMs = now;
                    long delayWait = lastFinishMs == 0 ? 0 : lastFinishMs + delayMs - now;
                    if (tokens >= 1.0 && delayWait <= 0) {
                        tokens -= 1.0;
                        return;
                    }
                    long tokenWait = tokens >= 1.0 ? 0 : (long) Math.ceil((1.0 - tokens) / refillPerMs);
                    sleepMs = Math.max(1L, Math.max(tokenWait, Math.max(0, delayWait)));
                }
                Thread.sleep(Math.min(sleepMs, 5_000L));
            }
        }

        void release() {
            synchronized (this) {
                lastFinishMs = System.currentTimeMillis();
            }
            inFlight.release();
        }
    }
}

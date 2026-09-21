package ai.interviewhq.crawler.browser;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomized pauses so sequential page loads look like a person reading,
 * not a tight bot loop.
 */
public final class HumanDelay {

    private HumanDelay() {
    }

    public static long between(int minMs, int maxMs) {
        int lo = Math.max(0, Math.min(minMs, maxMs));
        int hi = Math.max(minMs, maxMs);
        if (hi <= lo) {
            return lo;
        }
        return ThreadLocalRandom.current().nextLong(lo, (long) hi + 1);
    }

    public static void sleep(int minMs, int maxMs) throws InterruptedException {
        Thread.sleep(between(minMs, maxMs));
    }

    public static long exponentialJitter(int attempt, int minMs, int maxMs) {
        long exp = (long) minMs * (1L << Math.min(Math.max(attempt, 0), 4));
        long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, minMs / 3));
        return Math.min(exp + jitter, maxMs);
    }
}

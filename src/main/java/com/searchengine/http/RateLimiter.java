package com.searchengine.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-client token buckets: each client may make {@code ratePerSecond}
 * requests per second on average, with bursts up to {@code burst}. Keeps a
 * single public server usable when one client floods it.
 * <p>
 * Idle clients are forgotten periodically, so memory stays bounded by the
 * number of recently active clients.
 */
public final class RateLimiter {

    private static final long IDLE_NANOS = 10L * 60 * 1_000_000_000L;

    private static final int CLEANUP_EVERY = 10_000;

    private final double ratePerSecond;

    private final double burst;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private final AtomicLong calls = new AtomicLong();

    private static final class Bucket {

        double tokens;

        long updated;

        Bucket(double tokens, long now) {

            this.tokens = tokens;

            this.updated = now;
        }
    }

    public RateLimiter(double ratePerSecond, double burst) {

        this.ratePerSecond = ratePerSecond;

        this.burst = burst;
    }

    /**
     * Take {@code cost} tokens from the client's bucket.
     *
     * @return 0 if allowed, otherwise the whole seconds to wait before retrying
     */
    public long acquire(String client, double cost) {

        return acquire(client, cost, System.nanoTime());
    }

    long acquire(String client, double cost, long now) {

        if (calls.incrementAndGet() % CLEANUP_EVERY == 0) {

            buckets.values().removeIf(b -> now - b.updated > IDLE_NANOS);
        }

        Bucket bucket = buckets.computeIfAbsent(client, k -> new Bucket(burst, now));

        synchronized (bucket) {

            double elapsed = (now - bucket.updated) / 1e9;

            bucket.tokens = Math.min(burst, bucket.tokens + elapsed * ratePerSecond);

            bucket.updated = now;

            if (bucket.tokens >= cost) {

                bucket.tokens -= cost;

                return 0;
            }

            return (long) Math.ceil((cost - bucket.tokens) / ratePerSecond);
        }
    }

    int trackedClients() {

        return buckets.size();
    }
}

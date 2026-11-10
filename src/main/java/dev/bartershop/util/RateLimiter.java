package dev.bartershop.util;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small sliding-window limiter keyed by player and action. State survives
 * reconnects for the lifetime of the process, so reconnecting cannot reset a
 * spam budget. Old buckets are pruned on access.
 */
public final class RateLimiter {

    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public boolean allow(UUID player, String action, int maxHits, long windowMillis) {
        if (player == null || maxHits <= 0 || windowMillis <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - windowMillis;
        String key = player + ":" + action;
        Deque<Long> bucket = hits.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) {
                bucket.removeFirst();
            }
            if (bucket.size() >= maxHits) {
                return false;
            }
            bucket.addLast(now);
            return true;
        }
    }

    /** Number of actions inside the current window; useful for diagnostics/tests. */
    public int count(UUID player, String action, long windowMillis) {
        if (player == null) {
            return 0;
        }
        Deque<Long> bucket = hits.get(player + ":" + action);
        if (bucket == null) {
            return 0;
        }
        long cutoff = System.currentTimeMillis() - windowMillis;
        synchronized (bucket) {
            while (!bucket.isEmpty() && bucket.peekFirst() < cutoff) {
                bucket.removeFirst();
            }
            return bucket.size();
        }
    }

    public void clear(UUID player) {
        if (player == null) {
            return;
        }
        hits.keySet().removeIf(key -> key.startsWith(player + ":"));
    }
}

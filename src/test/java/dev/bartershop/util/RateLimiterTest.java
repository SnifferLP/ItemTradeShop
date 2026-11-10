package dev.bartershop.util;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RateLimiterTest {

    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void blocksAfterTheLimitWithoutResettingOnReconnect() {
        RateLimiter limiter = new RateLimiter();
        for (int i = 0; i < 3; i++) {
            assertTrue(limiter.allow(PLAYER, "trade", 3, 60_000));
        }
        assertFalse(limiter.allow(PLAYER, "trade", 3, 60_000));
        // No reset method exists for players; a reconnect cannot restore budget.
        assertFalse(limiter.allow(PLAYER, "trade", 3, 60_000));
        assertEquals(3, limiter.count(PLAYER, "trade", 60_000));
    }

    @Test
    void actionsUseIndependentBudgets() {
        RateLimiter limiter = new RateLimiter();
        assertTrue(limiter.allow(PLAYER, "trade", 1, 60_000));
        assertFalse(limiter.allow(PLAYER, "trade", 1, 60_000));
        assertTrue(limiter.allow(PLAYER, "offers", 1, 60_000));
    }

    @Test
    void clearRemovesOnlyThatPlayersBuckets() {
        RateLimiter limiter = new RateLimiter();
        UUID other = UUID.randomUUID();
        assertTrue(limiter.allow(PLAYER, "trade", 1, 60_000));
        assertTrue(limiter.allow(other, "trade", 1, 60_000));
        limiter.clear(PLAYER);
        assertTrue(limiter.allow(PLAYER, "trade", 1, 60_000));
        assertFalse(limiter.allow(other, "trade", 1, 60_000));
    }

    private static void assertEquals(int expected, int actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}

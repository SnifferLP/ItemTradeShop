package dev.bartershop.core.model;

import java.util.Objects;

/** Persistent, display-ready activity counters for one shop. */
public record ShopStats(
        int totalTrades,
        long itemsIn,
        long itemsOut,
        long lastTradeAt,
        int uniqueCustomers
) {

    public static final ShopStats EMPTY = new ShopStats(0, 0, 0, 0, 0);

    public ShopStats {
        if (totalTrades < 0 || itemsIn < 0 || itemsOut < 0 || lastTradeAt < 0 || uniqueCustomers < 0) {
            throw new IllegalArgumentException("statistics must not be negative");
        }
    }

    public ShopStats afterTrade(long incoming, long outgoing, long timestamp, boolean newCustomer) {
        return new ShopStats(
                totalTrades + 1,
                itemsIn + Math.max(0, incoming),
                itemsOut + Math.max(0, outgoing),
                Math.max(lastTradeAt, timestamp),
                uniqueCustomers + (newCustomer ? 1 : 0));
    }

    public long totalItems() {
        return itemsIn + itemsOut;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ShopStats stats
                && totalTrades == stats.totalTrades
                && itemsIn == stats.itemsIn
                && itemsOut == stats.itemsOut
                && lastTradeAt == stats.lastTradeAt
                && uniqueCustomers == stats.uniqueCustomers;
    }

    @Override
    public int hashCode() {
        return Objects.hash(totalTrades, itemsIn, itemsOut, lastTradeAt, uniqueCustomers);
    }
}

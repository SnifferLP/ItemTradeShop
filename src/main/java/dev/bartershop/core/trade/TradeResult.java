package dev.bartershop.core.trade;

import java.util.Objects;

/** Outcome of a transaction attempt. */
public record TradeResult(boolean success, FailureReason failure, TradePlan plan) {

    public static TradeResult success(TradePlan plan) {
        return new TradeResult(true, null, Objects.requireNonNull(plan, "plan"));
    }

    public static TradeResult failure(FailureReason reason) {
        return new TradeResult(false, Objects.requireNonNull(reason, "reason"), null);
    }
}

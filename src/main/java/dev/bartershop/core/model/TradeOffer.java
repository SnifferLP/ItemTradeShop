package dev.bartershop.core.model;

import dev.bartershop.core.item.ItemData;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A single item-for-item offer. The customer hands over every
 * {@link PaymentRequirement} and receives {@code receiveItem}.
 *
 * <p>For {@link TradeMode#SELL} the payment becomes pending owner proceeds; for
 * {@link TradeMode#BUY} it is added to the shop's item stock so the owner can
 * resell it. Amounts are independent of stack size and may span multiple
 * stacks. An offer with a positive {@code expiresAt} cannot be executed at or
 * after that instant; expiry never changes stock or proceeds by itself.</p>
 */
public record TradeOffer(
        UUID id,
        TradeMode mode,
        List<PaymentRequirement> payments,
        ItemData receiveItem,
        int receiveAmount,
        boolean exactPay,
        long expiresAt
) {

    public static final int MAX_AMOUNT = 54 * 64;
    public static final int MAX_PAYMENT_TYPES = 6;

    public TradeOffer {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(payments, "payments");
        Objects.requireNonNull(receiveItem, "receiveItem");
        payments = List.copyOf(payments);
        if (payments.isEmpty() || payments.size() > MAX_PAYMENT_TYPES) {
            throw new IllegalArgumentException("offers require between 1 and " + MAX_PAYMENT_TYPES + " payments");
        }
        for (PaymentRequirement payment : payments) {
            Objects.requireNonNull(payment, "payment");
        }
        if (receiveAmount < 1 || receiveAmount > MAX_AMOUNT) {
            throw new IllegalArgumentException("receive amount out of range: " + receiveAmount);
        }
        if (expiresAt < 0) {
            throw new IllegalArgumentException("expiry must not be negative");
        }
    }

    /** Backwards-compatible single-payment constructor. */
    public TradeOffer(UUID id, TradeMode mode, ItemData payItem, int payAmount,
                      ItemData receiveItem, int receiveAmount, boolean exactPay) {
        this(id, mode,
                List.of(new PaymentRequirement(payItem, payAmount, exactPay)),
                receiveItem, receiveAmount, exactPay, 0L);
    }

    public static TradeOffer create(TradeMode mode, ItemData payItem, int payAmount,
                                    ItemData receiveItem, int receiveAmount, boolean exactPay) {
        return new TradeOffer(UUID.randomUUID(), mode, payItem, payAmount, receiveItem, receiveAmount, exactPay);
    }

    public static TradeOffer create(TradeMode mode, List<PaymentRequirement> payments,
                                    ItemData receiveItem, int receiveAmount, long expiresAt) {
        return new TradeOffer(UUID.randomUUID(), mode, payments, receiveItem, receiveAmount,
                payments.stream().allMatch(PaymentRequirement::exact), expiresAt);
    }

    public TradeOffer withMode(TradeMode newMode) {
        return new TradeOffer(id, newMode, payments, receiveItem, receiveAmount, exactPay, expiresAt);
    }

    public TradeOffer withPay(ItemData item, int amount) {
        return withPayments(List.of(new PaymentRequirement(item, amount, exactPay)));
    }

    public TradeOffer withPayments(List<PaymentRequirement> newPayments) {
        boolean exact = newPayments.stream().allMatch(PaymentRequirement::exact);
        return new TradeOffer(id, mode, newPayments, receiveItem, receiveAmount, exact, expiresAt);
    }

    public TradeOffer withReceive(ItemData item, int amount) {
        return new TradeOffer(id, mode, payments, item, amount, exactPay, expiresAt);
    }

    public TradeOffer withExactPay(boolean exact) {
        List<PaymentRequirement> updated = payments.stream()
                .map(payment -> new PaymentRequirement(payment.item(), payment.amount(), exact))
                .toList();
        return new TradeOffer(id, mode, updated, receiveItem, receiveAmount, exact, expiresAt);
    }

    public TradeOffer withExpiry(long newExpiresAt) {
        return new TradeOffer(id, mode, payments, receiveItem, receiveAmount, exactPay, newExpiresAt);
    }

    /** The first payment, retained for compact single-item displays. */
    public ItemData payItem() {
        return payments.get(0).item();
    }

    /** Sum of all required payment items. */
    public int payAmount() {
        int total = 0;
        for (PaymentRequirement payment : payments) {
            total += payment.amount();
        }
        return total;
    }

    public String payName() {
        if (payments.size() == 1) {
            return displayName(payments.get(0).item());
        }
        return paymentSummary();
    }

    public String receiveName() {
        return displayName(receiveItem);
    }

    public String paymentSummary() {
        StringBuilder out = new StringBuilder();
        for (PaymentRequirement payment : payments) {
            if (out.length() > 0) {
                out.append(" + ");
            }
            out.append(payment.amount()).append("x ").append(displayName(payment.item()));
        }
        return out.toString();
    }

    public boolean hasExpiry() {
        return expiresAt > 0;
    }

    public boolean isExpired(long nowMillis) {
        return hasExpiry() && nowMillis >= expiresAt;
    }

    public long remainingMillis(long nowMillis) {
        return hasExpiry() ? Math.max(0L, expiresAt - nowMillis) : -1L;
    }

    public String describe() {
        return mode.display() + " " + paymentSummary()
                + " for " + receiveAmount + "x " + receiveName();
    }

    private static String displayName(ItemData item) {
        return item.prettyName();
    }
}

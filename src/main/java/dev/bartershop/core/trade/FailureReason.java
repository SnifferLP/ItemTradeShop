package dev.bartershop.core.trade;

/** Every way a transaction can be rejected. Mapped to player-facing messages by the plugin layer. */
public enum FailureReason {
    SHOP_DISABLED,
    SHOP_ORPHANED,
    OFFER_MISSING,
    OFFER_EXPIRED,
    OWNER_CANNOT_TRADE,
    MAINTENANCE,
    INSUFFICIENT_PAYMENT,
    INVENTORY_FULL,
    OUT_OF_STOCK,
    STORAGE_FULL,
    BUSY,
    INTERNAL_ERROR
}

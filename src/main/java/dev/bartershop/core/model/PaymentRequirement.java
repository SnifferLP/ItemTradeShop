package dev.bartershop.core.model;

import dev.bartershop.core.item.ItemData;

import java.util.Objects;

/**
 * One item type a customer must hand over to complete an offer.
 *
 * <p>Offers can require several distinct item types. Requirements are always
 * validated together and all-or-nothing, so a partially paid trade can never
 * be committed.</p>
 *
 * @param item   exact item identity selected by the shop owner
 * @param amount required quantity, independent of stack size
 * @param exact  true to require matching metadata, false to accept any variant
 *               of the same material
 */
public record PaymentRequirement(ItemData item, int amount, boolean exact) {

    public static final int MAX_AMOUNT = 54 * 64;

    public PaymentRequirement {
        Objects.requireNonNull(item, "item");
        if (amount < 1 || amount > MAX_AMOUNT) {
            throw new IllegalArgumentException("payment amount out of range: " + amount);
        }
    }

    public String describe() {
        return amount + "x " + item.prettyName();
    }
}

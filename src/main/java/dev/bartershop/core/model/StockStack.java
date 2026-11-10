package dev.bartershop.core.model;

import dev.bartershop.core.item.ItemData;

import java.util.Objects;

/** An immutable stack of items held by a shop. {@code amount} never exceeds the item's max stack size. */
public record StockStack(ItemData item, int amount) {

    public StockStack {
        Objects.requireNonNull(item, "item");
        if (amount < 1) {
            throw new IllegalArgumentException("stack amount must be positive");
        }
        if (amount > item.maxStackSize()) {
            throw new IllegalArgumentException("amount " + amount + " exceeds max stack " + item.maxStackSize());
        }
    }

    public StockStack withAmount(int newAmount) {
        return new StockStack(item, newAmount);
    }

    public String label() {
        if (item.displayName() != null && !item.displayName().isBlank()) {
            return item.displayName();
        }
        String material = item.material().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(material.charAt(0)) + material.substring(1);
    }
}

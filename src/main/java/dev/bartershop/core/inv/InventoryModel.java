package dev.bartershop.core.inv;

import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.item.ItemMatcher;
import dev.bartershop.core.model.StockStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Pure, slot-accurate model of a player's storage inventory (main inventory
 * plus hotbar, excluding armour and offhand). All payment, delivery and
 * collection calculations are performed on this model before the real
 * inventory is touched, which makes full-inventory and partial-collection
 * behaviour deterministic and testable.
 *
 * <p>Slot indices map 1:1 to Bukkit player inventory slots 0-35 in the
 * adapter, so applying a model is a simple diff of changed slots.</p>
 */
public final class InventoryModel {

    public record HeldItem(ItemData item, int amount) {
        public HeldItem {
            Objects.requireNonNull(item, "item");
            if (amount < 1) {
                throw new IllegalArgumentException("amount must be positive");
            }
        }

        public HeldItem withAmount(int newAmount) {
            return new HeldItem(item, newAmount);
        }
    }

    private final HeldItem[] slots;

    private InventoryModel(HeldItem[] slots) {
        this.slots = slots;
    }

    public static InventoryModel empty(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("inventory size must be positive");
        }
        return new InventoryModel(new HeldItem[size]);
    }

    public static InventoryModel of(List<HeldItem> contents) {
        HeldItem[] array = contents.toArray(new HeldItem[0]);
        return new InventoryModel(array);
    }

    public int size() {
        return slots.length;
    }

    public HeldItem get(int slot) {
        return slots[slot];
    }

    public void set(int slot, HeldItem item) {
        slots[slot] = item;
    }

    public int countMatching(ItemData item, boolean exact) {
        int total = 0;
        for (HeldItem held : slots) {
            if (held != null && ItemMatcher.matches(held.item(), item, exact)) {
                total += held.amount();
            }
        }
        return total;
    }

    public int totalItems() {
        int total = 0;
        for (HeldItem held : slots) {
            if (held != null) {
                total += held.amount();
            }
        }
        return total;
    }

    /** Number of items that could still be added, accounting for partial stacks. */
    public int roomFor(ItemData item) {
        int room = 0;
        for (HeldItem held : slots) {
            if (held == null) {
                room += item.maxStackSize();
            } else if (held.item().sameMetadata(item)) {
                room += item.maxStackSize() - held.amount();
            }
        }
        return room;
    }

    /**
     * Removes exactly {@code amount} matching items, all-or-nothing.
     */
    public boolean removeMatching(ItemData item, int amount, boolean exact) {
        return !removeMatchingCollect(item, amount, exact).isEmpty();
    }

    /**
     * Removes matching items all-or-nothing and returns the exact stacks that
     * were taken. This preserves metadata variants accepted by loose matching
     * so the owner receives the real items the customer paid, never a copy of
     * the offer's template item.
     */
    public List<StockStack> removeMatchingCollect(ItemData item, int amount, boolean exact) {
        Objects.requireNonNull(item, "item");
        if (amount <= 0 || countMatching(item, exact) < amount) {
            return List.of();
        }
        List<StockStack> removed = new ArrayList<>();
        int remaining = amount;
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            HeldItem held = slots[i];
            if (held == null || !ItemMatcher.matches(held.item(), item, exact)) {
                continue;
            }
            int taken = Math.min(held.amount(), remaining);
            remaining -= taken;
            removed.add(new StockStack(held.item(), taken));
            if (taken == held.amount()) {
                slots[i] = null;
            } else {
                slots[i] = held.withAmount(held.amount() - taken);
            }
        }
        return remaining == 0 ? removed : List.of();
    }

    /** Adds items, merging exact-metadata stacks and using empty slots. Returns the leftover amount. */
    public int add(ItemData item, int amount) {
        Objects.requireNonNull(item, "item");
        if (amount <= 0) {
            return 0;
        }
        int remaining = amount;
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            HeldItem held = slots[i];
            if (held == null || !held.item().sameMetadata(item) || held.amount() >= item.maxStackSize()) {
                continue;
            }
            int moved = Math.min(item.maxStackSize() - held.amount(), remaining);
            slots[i] = held.withAmount(held.amount() + moved);
            remaining -= moved;
        }
        for (int i = 0; i < slots.length && remaining > 0; i++) {
            if (slots[i] != null) {
                continue;
            }
            int moved = Math.min(item.maxStackSize(), remaining);
            slots[i] = new HeldItem(item, moved);
            remaining -= moved;
        }
        return remaining;
    }

    /**
     * Removes up to {@code amount} from one slot, returning the amount removed.
     */
    public int removeFromSlot(int slot, int amount) {
        HeldItem held = slots[slot];
        if (held == null || amount <= 0) {
            return 0;
        }
        int taken = Math.min(held.amount(), amount);
        if (taken == held.amount()) {
            slots[slot] = null;
        } else {
            slots[slot] = held.withAmount(held.amount() - taken);
        }
        return taken;
    }

    public InventoryModel copy() {
        return new InventoryModel(slots.clone());
    }

    public void copyFrom(InventoryModel other) {
        if (other.slots.length != slots.length) {
            throw new IllegalArgumentException("inventory size mismatch");
        }
        System.arraycopy(other.slots, 0, slots, 0, slots.length);
    }

    /** Slot indices whose contents differ from {@code before}, in ascending order. */
    public List<Integer> changedSlots(InventoryModel before) {
        if (before.size() != size()) {
            throw new IllegalArgumentException("inventory size mismatch");
        }
        List<Integer> changed = new ArrayList<>();
        for (int i = 0; i < slots.length; i++) {
            if (!Objects.equals(slots[i], before.slots[i])) {
                changed.add(i);
            }
        }
        return changed;
    }

    public List<HeldItem> contents() {
        return Arrays.asList(slots.clone());
    }

    public boolean isEmpty() {
        for (HeldItem held : slots) {
            if (held != null) {
                return false;
            }
        }
        return true;
    }
}

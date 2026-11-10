package dev.bartershop.core.stock;

import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.item.ItemMatcher;
import dev.bartershop.core.model.StockStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Slot-limited shop stock. Each entry is one inventory slot containing at most
 * one max-size stack. Capacity is derived from the linked chest size at shop
 * creation (27 slots for a single chest, 54 for a double chest).
 *
 * <p>Not thread-safe: callers must hold the owning shop's lock. This class is
 * deliberately free of Bukkit types so capacity and stack arithmetic can be
 * tested exhaustively.</p>
 */
public final class StockStorage {

    private final int capacitySlots;
    private final List<StockStack> stacks;

    public static final int MAX_CAPACITY_SLOTS = dev.bartershop.core.model.Shop.MAX_CAPACITY_SLOTS;

    public StockStorage(int capacitySlots) {
        validateCapacity(capacitySlots);
        this.capacitySlots = capacitySlots;
        this.stacks = new ArrayList<>();
    }

    private StockStorage(int capacitySlots, List<StockStack> initial) {
        validateCapacity(capacitySlots);
        if (initial.size() > capacitySlots) {
            throw new IllegalArgumentException("stock exceeds capacity");
        }
        this.capacitySlots = capacitySlots;
        this.stacks = new ArrayList<>(initial);
    }

    private static void validateCapacity(int capacitySlots) {
        if (capacitySlots < 1 || capacitySlots > MAX_CAPACITY_SLOTS) {
            throw new IllegalArgumentException("capacity must be between 1 and "
                    + MAX_CAPACITY_SLOTS + " slots");
        }
    }

    /** Restores a trusted snapshot, preserving stack boundaries exactly. */
    public static StockStorage restore(int capacitySlots, List<StockStack> stacks) {
        return new StockStorage(capacitySlots, stacks);
    }

    public int capacitySlots() {
        return capacitySlots;
    }

    public int slotsUsed() {
        return stacks.size();
    }

    public int freeSlots() {
        return capacitySlots - stacks.size();
    }

    public List<StockStack> stacks() {
        return Collections.unmodifiableList(stacks);
    }

    public int count(ItemData item, boolean exact) {
        int total = 0;
        for (StockStack stack : stacks) {
            if (ItemMatcher.matches(stack.item(), item, exact)) {
                total += stack.amount();
            }
        }
        return total;
    }

    public int totalItems() {
        int total = 0;
        for (StockStack stack : stacks) {
            total += stack.amount();
        }
        return total;
    }

    /**
     * Adds items, merging into existing exact-metadata stacks first and then
     * claiming free slots. Returns the number of items that did not fit.
     */
    public int add(ItemData item, int amount) {
        Objects.requireNonNull(item, "item");
        if (amount <= 0) {
            return 0;
        }
        int remaining = amount;
        for (int i = 0; i < stacks.size() && remaining > 0; i++) {
            StockStack stack = stacks.get(i);
            if (!stack.item().sameMetadata(item) || stack.amount() >= item.maxStackSize()) {
                continue;
            }
            int room = item.maxStackSize() - stack.amount();
            int moved = Math.min(room, remaining);
            stacks.set(i, stack.withAmount(stack.amount() + moved));
            remaining -= moved;
        }
        while (remaining > 0 && stacks.size() < capacitySlots) {
            int moved = Math.min(item.maxStackSize(), remaining);
            stacks.add(new StockStack(item, moved));
            remaining -= moved;
        }
        return remaining;
    }

    public boolean canAccept(ItemData item, int amount) {
        if (amount <= 0) {
            return true;
        }
        int room = 0;
        for (StockStack stack : stacks) {
            if (stack.item().sameMetadata(item)) {
                room += item.maxStackSize() - stack.amount();
            }
        }
        room += freeSlots() * item.maxStackSize();
        return room >= amount;
    }

    /**
     * Removes exactly {@code amount} matching items. All-or-nothing: returns
     * false and changes nothing when there is insufficient stock.
     */
    public boolean remove(ItemData item, int amount, boolean exact) {
        Objects.requireNonNull(item, "item");
        if (amount <= 0) {
            return false;
        }
        if (count(item, exact) < amount) {
            return false;
        }
        int remaining = amount;
        for (int i = 0; i < stacks.size() && remaining > 0; ) {
            StockStack stack = stacks.get(i);
            if (!ItemMatcher.matches(stack.item(), item, exact)) {
                i++;
                continue;
            }
            int taken = Math.min(stack.amount(), remaining);
            remaining -= taken;
            if (taken == stack.amount()) {
                stacks.remove(i);
            } else {
                stacks.set(i, stack.withAmount(stack.amount() - taken));
                i++;
            }
        }
        return remaining == 0;
    }

    /** Removes up to {@code amount} items from a specific slot, returning how many were removed. */
    public int removeAt(int slot, int amount) {
        if (slot < 0 || slot >= stacks.size() || amount <= 0) {
            return 0;
        }
        StockStack stack = stacks.get(slot);
        int taken = Math.min(amount, stack.amount());
        if (taken == stack.amount()) {
            stacks.remove(slot);
        } else {
            stacks.set(slot, stack.withAmount(stack.amount() - taken));
        }
        return taken;
    }

    public StockStack at(int slot) {
        return stacks.get(slot);
    }

    public StockStorage copy() {
        return new StockStorage(capacitySlots, stacks);
    }

    /**
     * Returns a copy with a different slot capacity. The contents are preserved
     * exactly and the resize fails when existing stacks would no longer fit.
     */
    public StockStorage withCapacity(int newCapacity) {
        validateCapacity(newCapacity);
        return new StockStorage(newCapacity, stacks);
    }

    /** Replaces this storage's contents with a snapshot, used for transaction rollback. */
    public void replaceWith(StockStorage other) {
        if (other.capacitySlots != capacitySlots) {
            throw new IllegalArgumentException("capacity mismatch");
        }
        stacks.clear();
        stacks.addAll(other.stacks);
    }
}

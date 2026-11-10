package dev.bartershop.core.proceeds;

import dev.bartershop.core.model.StockStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Pending owner earnings. Unlike {@link dev.bartershop.core.stock.StockStorage}
 * this has no slot limit: it is a mailbox, not a container, so items can never
 * be discarded because the owner's inventory was full.
 *
 * <p>Used both for per-shop proceeds and for a deleted shop's recovered
 * mailbox. Not thread-safe; callers synchronise through the owning shop.</p>
 */
public final class ProceedsStorage {

    private final List<StockStack> stacks = new ArrayList<>();

    public static ProceedsStorage restore(List<StockStack> stacks) {
        ProceedsStorage storage = new ProceedsStorage();
        storage.stacks.addAll(stacks);
        return storage;
    }

    public void add(StockStack stack) {
        add(stack.item(), stack.amount());
    }

    /** Adds items, merging exact-metadata stacks and splitting oversized amounts into stack-sized entries. */
    public void add(dev.bartershop.core.item.ItemData item, int amount) {
        Objects.requireNonNull(item, "item");
        if (amount <= 0) {
            return;
        }
        int remaining = amount;
        for (int i = 0; i < stacks.size() && remaining > 0; i++) {
            StockStack stack = stacks.get(i);
            if (!stack.item().sameMetadata(item) || stack.amount() >= item.maxStackSize()) {
                continue;
            }
            int moved = Math.min(item.maxStackSize() - stack.amount(), remaining);
            stacks.set(i, stack.withAmount(stack.amount() + moved));
            remaining -= moved;
        }
        while (remaining > 0) {
            int moved = Math.min(item.maxStackSize(), remaining);
            stacks.add(new StockStack(item, moved));
            remaining -= moved;
        }
    }

    public List<StockStack> stacks() {
        return Collections.unmodifiableList(stacks);
    }

    public boolean isEmpty() {
        return stacks.isEmpty();
    }

    public int totalItems() {
        int total = 0;
        for (StockStack stack : stacks) {
            total += stack.amount();
        }
        return total;
    }

    public int countOf(dev.bartershop.core.item.ItemData item) {
        int total = 0;
        for (StockStack stack : stacks) {
            if (stack.item().sameMetadata(item)) {
                total += stack.amount();
            }
        }
        return total;
    }

    /** Removes part of one entry, returning the removed amount. */
    public int removeAt(int index, int amount) {
        if (index < 0 || index >= stacks.size() || amount <= 0) {
            return 0;
        }
        StockStack stack = stacks.get(index);
        int taken = Math.min(amount, stack.amount());
        if (taken == stack.amount()) {
            stacks.remove(index);
        } else {
            stacks.set(index, stack.withAmount(stack.amount() - taken));
        }
        return taken;
    }

    public StockStack at(int index) {
        return stacks.get(index);
    }

    public ProceedsStorage copy() {
        return restore(stacks);
    }

    public void replaceWith(ProceedsStorage other) {
        stacks.clear();
        stacks.addAll(other.stacks);
    }
}

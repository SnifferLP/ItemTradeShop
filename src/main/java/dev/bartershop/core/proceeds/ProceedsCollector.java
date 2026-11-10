package dev.bartershop.core.proceeds;

import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.model.StockStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans collection of pending proceeds into an inventory. Collection is
 * best-effort and partial: as many items as possible are moved, and the rest
 * stay pending. No item is ever destroyed because an inventory is full.
 */
public final class ProceedsCollector {

    private ProceedsCollector() {
    }

    public static CollectionPlan plan(ProceedsStorage proceeds, InventoryModel inventory) {
        ProceedsStorage after = proceeds.copy();
        InventoryModel model = inventory.copy();
        List<StockStack> collected = new ArrayList<>();

        int index = 0;
        while (index < after.stacks().size()) {
            StockStack stack = after.at(index);
            int leftover = model.add(stack.item(), stack.amount());
            int accepted = stack.amount() - leftover;
            if (accepted <= 0) {
                index++;
                continue;
            }
            int remaining = accepted;
            while (remaining > 0) {
                int chunk = Math.min(stack.item().maxStackSize(), remaining);
                collected.add(new StockStack(stack.item(), chunk));
                remaining -= chunk;
            }
            if (accepted == stack.amount()) {
                after.removeAt(index, accepted);
                // The next entry shifted into this index; do not advance.
            } else {
                after.removeAt(index, accepted);
                index++;
            }
        }

        return new CollectionPlan(proceeds.copy(), after, inventory.copy(), model, collected);
    }
}

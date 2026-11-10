package dev.bartershop.core.proceeds;

import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.model.StockStack;

import java.util.List;
import java.util.Objects;

/**
 * A validated, reversible plan for moving some or all pending proceeds into a
 * player's inventory. Items that do not fit remain in {@link #proceedsAfter()};
 * they are never discarded.
 */
public final class CollectionPlan {

    private final ProceedsStorage proceedsBefore;
    private final ProceedsStorage proceedsAfter;
    private final InventoryModel inventoryBefore;
    private final InventoryModel inventoryAfter;
    private final List<StockStack> collected;
    private final long timestampMillis = System.currentTimeMillis();
    private volatile String journalId;

    public CollectionPlan(ProceedsStorage proceedsBefore,
                          ProceedsStorage proceedsAfter,
                          InventoryModel inventoryBefore,
                          InventoryModel inventoryAfter,
                          List<StockStack> collected) {
        this.proceedsBefore = Objects.requireNonNull(proceedsBefore, "proceedsBefore");
        this.proceedsAfter = Objects.requireNonNull(proceedsAfter, "proceedsAfter");
        this.inventoryBefore = Objects.requireNonNull(inventoryBefore, "inventoryBefore");
        this.inventoryAfter = Objects.requireNonNull(inventoryAfter, "inventoryAfter");
        this.collected = List.copyOf(collected);
    }

    public ProceedsStorage proceedsBefore() {
        return proceedsBefore;
    }

    public ProceedsStorage proceedsAfter() {
        return proceedsAfter;
    }

    public InventoryModel inventoryBefore() {
        return inventoryBefore;
    }

    public InventoryModel inventoryAfter() {
        return inventoryAfter;
    }

    public List<StockStack> collected() {
        return collected;
    }

    public boolean isEmpty() {
        return collected.isEmpty();
    }

    public int collectedItems() {
        int total = 0;
        for (StockStack stack : collected) {
            total += stack.amount();
        }
        return total;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public void applyInventory(InventoryModel current) {
        current.copyFrom(inventoryAfter);
    }

    public void revertInventory(InventoryModel current) {
        current.copyFrom(inventoryBefore);
    }

    public String journalId() {
        return journalId;
    }

    public void setJournalId(String journalId) {
        this.journalId = journalId;
    }
}

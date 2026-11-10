package dev.bartershop.core;

import dev.bartershop.TestData;
import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.proceeds.CollectionPlan;
import dev.bartershop.core.proceeds.ProceedsCollector;
import dev.bartershop.core.proceeds.ProceedsStorage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeded property test for partial collection: no item is ever lost, nothing
 * is duplicated, and repeated collection always terminates with at most the
 * pending items still waiting.
 */
class ProceedsStressTest {

    private static final List<ItemData> ITEMS = List.of(
            TestData.item("DIAMOND", "default", 64),
            TestData.item("EMERALD", "default", 64),
            TestData.item("ARROW", "default", 16),
            TestData.item("SHIELD", "default", 1));

    @Test
    void randomizedCollectionsConserveEveryItem() {
        for (long seed : new long[]{9L, 31L, 808L}) {
            Random random = new Random(seed);
            for (int round = 0; round < 150; round++) {
                ProceedsStorage proceeds = new ProceedsStorage();
                int entries = random.nextInt(15) + 1;
                for (int i = 0; i < entries; i++) {
                    ItemData item = ITEMS.get(random.nextInt(ITEMS.size()));
                    proceeds.add(item, 1 + random.nextInt(item.maxStackSize() * 4));
                }
                InventoryModel inventory = InventoryModel.empty(36);
                int stacks = random.nextInt(37);
                for (int i = 0; i < stacks; i++) {
                    ItemData item = ITEMS.get(random.nextInt(ITEMS.size()));
                    inventory.add(item, 1 + random.nextInt(item.maxStackSize()));
                }

                Map<String, Integer> before = total(proceeds, inventory);
                CollectionPlan plan = ProceedsCollector.plan(proceeds, inventory);

                assertEquals(plan.proceedsBefore().totalItems(), proceeds.totalItems());
                assertEquals(plan.inventoryBefore().totalItems(), inventory.totalItems());
                assertEquals(plan.collectedItems(),
                        plan.proceedsBefore().totalItems() - plan.proceedsAfter().totalItems(),
                        "collected count must equal the removed pending count");
                assertEquals(before, total(plan.proceedsAfter(), plan.inventoryAfter()),
                        "collection must conserve items (seed " + seed + ", round " + round + ")");

                InventoryModel applied = inventory.copy();
                plan.applyInventory(applied);
                assertEquals(plan.inventoryAfter().totalItems(), applied.totalItems());
                plan.revertInventory(applied);
                assertEquals(inventory.totalItems(), applied.totalItems());

                // Repeating collection must converge: either everything fits or
                // the inventory is full and nothing more can move.
                ProceedsStorage pending = plan.proceedsAfter().copy();
                InventoryModel target = plan.inventoryAfter().copy();
                int guard = 0;
                while (!pending.isEmpty() && guard++ < 100) {
                    CollectionPlan next = ProceedsCollector.plan(pending, target);
                    if (next.isEmpty()) {
                        break;
                    }
                    pending = next.proceedsAfter();
                    target = next.inventoryAfter();
                }
                assertEquals(before, total(pending, target),
                        "repeated collection must conserve items");
                assertTrue(guard < 100, "collection did not converge");
                for (var stack : pending.stacks()) {
                    assertEquals(0, target.roomFor(stack.item()),
                            "collection stopped although room for " + stack.item().material() + " remained");
                }
            }
        }
    }

    private static Map<String, Integer> total(ProceedsStorage proceeds, InventoryModel inventory) {
        Map<String, Integer> map = new HashMap<>();
        for (var stack : proceeds.stacks()) {
            map.merge(stack.item().signature(), stack.amount(), Integer::sum);
        }
        for (InventoryModel.HeldItem held : inventory.contents()) {
            if (held != null) {
                map.merge(held.item().signature(), held.amount(), Integer::sum);
            }
        }
        return map;
    }
}

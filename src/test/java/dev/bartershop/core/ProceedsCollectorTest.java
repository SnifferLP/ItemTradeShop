package dev.bartershop.core;

import dev.bartershop.TestData;
import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.proceeds.CollectionPlan;
import dev.bartershop.core.proceeds.ProceedsCollector;
import dev.bartershop.core.proceeds.ProceedsStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProceedsCollectorTest {

    private static final ItemData DIAMOND = TestData.item("DIAMOND");
    private static final ItemData EMERALD = TestData.item("EMERALD");
    private static final ItemData STONE = TestData.item("STONE");

    @Test
    void completeCollectionMovesEverything() {
        ProceedsStorage proceeds = new ProceedsStorage();
        proceeds.add(DIAMOND, 100);
        InventoryModel inventory = InventoryModel.empty(36);

        CollectionPlan plan = ProceedsCollector.plan(proceeds, inventory);

        assertEquals(100, plan.collectedItems());
        assertTrue(plan.proceedsAfter().isEmpty());
        assertEquals(100, plan.inventoryAfter().countMatching(DIAMOND, true));
        assertEquals(100, plan.proceedsBefore().totalItems());
    }

    @Test
    void partialCollectionKeepsRemainderPending() {
        ProceedsStorage proceeds = new ProceedsStorage();
        proceeds.add(DIAMOND, 100);
        InventoryModel inventory = TestData.inventory(36, STONE, 35);

        CollectionPlan plan = ProceedsCollector.plan(proceeds, inventory);

        assertEquals(64, plan.collectedItems());
        assertEquals(36, plan.proceedsAfter().countOf(DIAMOND));
        assertEquals(64, plan.inventoryAfter().countMatching(DIAMOND, true));
        assertEquals(35 * 64 + 64, plan.inventoryAfter().totalItems());
    }

    @Test
    void fullInventoryCollectsNothingAndDiscardsNothing() {
        ProceedsStorage proceeds = new ProceedsStorage();
        proceeds.add(DIAMOND, 10);
        InventoryModel inventory = TestData.fullOf(STONE);

        CollectionPlan plan = ProceedsCollector.plan(proceeds, inventory);

        assertTrue(plan.isEmpty());
        assertEquals(10, plan.proceedsAfter().countOf(DIAMOND));
        assertEquals(0, plan.inventoryAfter().countMatching(DIAMOND, true));
        assertEquals(10, plan.proceedsBefore().totalItems());
    }

    @Test
    void mixedProceedsCollectAsMuchAsFits() {
        ProceedsStorage proceeds = new ProceedsStorage();
        proceeds.add(DIAMOND, 10);
        proceeds.add(EMERALD, 10);
        InventoryModel inventory = TestData.inventory(36, STONE, 35);

        CollectionPlan plan = ProceedsCollector.plan(proceeds, inventory);

        assertEquals(10, plan.collectedItems());
        assertEquals(10, plan.inventoryAfter().countMatching(DIAMOND, true));
        assertEquals(0, plan.inventoryAfter().countMatching(EMERALD, true));
        assertEquals(10, plan.proceedsAfter().countOf(EMERALD));
    }

    @Test
    void metadataOfCollectedItemsIsPreserved() {
        ItemData custom = TestData.item("DIAMOND", "custom", 64);
        ProceedsStorage proceeds = new ProceedsStorage();
        proceeds.add(custom, 5);
        InventoryModel inventory = InventoryModel.empty(36);

        CollectionPlan plan = ProceedsCollector.plan(proceeds, inventory);

        assertEquals(5, plan.inventoryAfter().countMatching(custom, true));
        assertTrue(plan.proceedsAfter().isEmpty());
        assertFalse(plan.collected().isEmpty());
        assertEquals(custom.signature(), plan.collected().get(0).item().signature());
    }

    @Test
    void planApplyAndRevertAreInverse() {
        ProceedsStorage proceeds = new ProceedsStorage();
        proceeds.add(DIAMOND, 20);
        InventoryModel inventory = InventoryModel.empty(36);

        CollectionPlan plan = ProceedsCollector.plan(proceeds, inventory);
        InventoryModel target = InventoryModel.empty(36);
        plan.applyInventory(target);
        assertEquals(20, target.countMatching(DIAMOND, true));
        plan.revertInventory(target);
        assertEquals(0, target.totalItems());
    }
}

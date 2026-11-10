package dev.bartershop.core;

import dev.bartershop.TestData;
import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.item.ItemData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryModelTest {

    @Test
    void addSplitsAcrossStacks() {
        ItemData diamond = TestData.item("DIAMOND");
        InventoryModel model = InventoryModel.empty(36);
        assertEquals(0, model.add(diamond, 100));
        assertEquals(100, model.countMatching(diamond, true));
        assertEquals(100, model.totalItems());
        assertEquals(diamond.maxStackSize(), model.get(0).amount());
        assertEquals(36, model.get(1).amount());
        assertNull(model.get(2));
    }

    @Test
    void addMergesOnlyExactMetadata() {
        ItemData sharp = TestData.item("DIAMOND_SWORD", "sharp", 1);
        ItemData plain = TestData.item("DIAMOND_SWORD", "plain", 1);
        InventoryModel model = InventoryModel.empty(4);
        model.add(sharp, 1);
        model.add(sharp, 1);
        model.add(plain, 1);
        assertEquals(2, model.countMatching(sharp, true));
        assertEquals(1, model.countMatching(plain, true));
        assertEquals(3, model.countMatching(sharp, false));
        assertEquals(3, model.totalItems());
    }

    @Test
    void removeIsAllOrNothingAndHonoursMatchingMode() {
        ItemData a = TestData.item("EMERALD", "a", 64);
        ItemData b = TestData.item("EMERALD", "b", 64);
        InventoryModel model = InventoryModel.empty(4);
        model.add(a, 5);
        model.add(b, 7);

        assertFalse(model.removeMatching(a, 6, true));
        assertEquals(12, model.totalItems());

        assertTrue(model.removeMatching(a, 10, false));
        assertEquals(2, model.totalItems());
        assertEquals(0, model.countMatching(a, true));
        assertEquals(2, model.countMatching(b, true));
    }

    @Test
    void fullInventoryRejectsNewItems() {
        ItemData stone = TestData.item("STONE");
        InventoryModel model = TestData.fullOf(stone);
        ItemData diamond = TestData.item("DIAMOND");
        assertEquals(1, model.add(diamond, 1));
        assertEquals(36 * 64, model.totalItems());
        assertEquals(0, model.roomFor(diamond));
    }

    @Test
    void roomCountsPartialStacksAndEmptySlots() {
        ItemData diamond = TestData.item("DIAMOND");
        InventoryModel model = InventoryModel.empty(4);
        model.add(diamond, 60);
        assertEquals(4 + 3 * 64, model.roomFor(diamond));
        model.add(diamond, 4);
        assertEquals(3 * 64, model.roomFor(diamond));
    }

    @Test
    void changedSlotsReportsOnlyDifferences() {
        ItemData diamond = TestData.item("DIAMOND");
        InventoryModel before = InventoryModel.empty(4);
        before.add(diamond, 10);
        InventoryModel after = before.copy();
        after.add(diamond, 5);
        assertEquals(List.of(0), after.changedSlots(before));
        after.set(3, new InventoryModel.HeldItem(diamond, 1));
        assertEquals(List.of(0, 3), after.changedSlots(before));
    }

    @Test
    void copyIsIndependent() {
        ItemData diamond = TestData.item("DIAMOND");
        InventoryModel model = InventoryModel.empty(2);
        model.add(diamond, 10);
        InventoryModel copy = model.copy();
        copy.removeMatching(diamond, 10, true);
        assertEquals(10, model.totalItems());
        assertEquals(0, copy.totalItems());
    }
}

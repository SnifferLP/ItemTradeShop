package dev.bartershop.core;

import dev.bartershop.TestData;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.stock.StockStorage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StockStorageTest {

    @Test
    void addFillsSlotsAndReportsLeftover() {
        ItemData diamond = TestData.item("DIAMOND");
        StockStorage stock = new StockStorage(2);
        assertEquals(28, stock.add(diamond, 156));
        assertEquals(2, stock.slotsUsed());
        assertEquals(128, stock.count(diamond, true));
        assertEquals(0, stock.freeSlots());
        assertEquals(28, stock.add(diamond, 28));
    }

    @Test
    void removeIsAllOrNothing() {
        ItemData diamond = TestData.item("DIAMOND");
        StockStorage stock = new StockStorage(2);
        stock.add(diamond, 100);
        assertFalse(stock.remove(diamond, 101, true));
        assertEquals(100, stock.count(diamond, true));
        assertTrue(stock.remove(diamond, 50, true));
        assertEquals(50, stock.count(diamond, true));
    }

    @Test
    void matchingIsMetadataSensitive() {
        ItemData a = TestData.item("PAPER", "a", 64);
        ItemData b = TestData.item("PAPER", "b", 64);
        StockStorage stock = new StockStorage(2);
        stock.add(a, 10);
        stock.add(b, 5);
        assertEquals(10, stock.count(a, true));
        assertEquals(15, stock.count(a, false));
        assertFalse(stock.remove(b, 6, true));
        assertTrue(stock.remove(a, 12, false));
        assertEquals(3, stock.totalItems());
    }

    @Test
    void fullCapacityRejectsOtherItems() {
        ItemData stone = TestData.item("STONE");
        ItemData diamond = TestData.item("DIAMOND");
        StockStorage stock = new StockStorage(1);
        assertEquals(0, stock.add(stone, 64));
        assertEquals(5, stock.add(diamond, 5));
        assertEquals(64, stock.totalItems());
        assertFalse(stock.canAccept(diamond, 1));
    }

    @Test
    void removeAtHandlesPartialStacks() {
        ItemData diamond = TestData.item("DIAMOND");
        StockStorage stock = new StockStorage(2);
        stock.add(diamond, 70);
        assertEquals(20, stock.removeAt(0, 20));
        assertEquals(50, stock.count(diamond, true));
        assertEquals(2, stock.slotsUsed());
        assertEquals(6, stock.removeAt(0, 6));
        assertEquals(44, stock.count(diamond, true));
    }

    @Test
    void copyIsIndependentAndReplaceWorks() {
        ItemData diamond = TestData.item("DIAMOND");
        StockStorage stock = new StockStorage(2);
        stock.add(diamond, 10);
        StockStorage copy = stock.copy();
        copy.remove(diamond, 10, true);
        assertEquals(10, stock.totalItems());
        stock.replaceWith(copy);
        assertEquals(0, stock.totalItems());
    }

    @Test
    void restorePreservesStackBoundaries() {
        ItemData diamond = TestData.item("DIAMOND");
        StockStorage stock = new StockStorage(2);
        stock.add(diamond, 100);
        StockStorage restored = StockStorage.restore(2, stock.stacks());
        assertEquals(2, restored.slotsUsed());
        assertEquals(64, restored.at(0).amount());
        assertEquals(36, restored.at(1).amount());
    }

    @Test
    void resizingPreservesContentsAndRejectsShrinkingBelowUsage() {
        ItemData diamond = TestData.item("DIAMOND");
        StockStorage stock = new StockStorage(27);
        stock.add(diamond, 64);
        stock.add(TestData.item("EMERALD"), 1);
        StockStorage grown = stock.withCapacity(81);
        assertEquals(81, grown.capacitySlots());
        assertEquals(2, grown.slotsUsed());
        assertEquals(64, grown.count(diamond, true));
        assertThrows(IllegalArgumentException.class, () -> stock.withCapacity(1));
    }

    @Test
    void capacityValidation() {
        assertThrows(IllegalArgumentException.class, () -> new StockStorage(0));
        assertThrows(IllegalArgumentException.class, () -> new StockStorage(StockStorage.MAX_CAPACITY_SLOTS + 1));
    }
}

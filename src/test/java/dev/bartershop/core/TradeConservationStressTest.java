package dev.bartershop.core;

import dev.bartershop.TestData;
import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.core.stock.StockStorage;
import dev.bartershop.core.trade.TradeCommit;
import dev.bartershop.core.trade.TradeEngine;
import dev.bartershop.core.trade.TradeResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adversarial, seeded stress test for the pure trade engine. Thousands of
 * randomized multi-payment trades run against a shop with mixed selling and
 * buying offers. After every attempt the test proves that no item was created
 * or destroyed and that rejected trades changed nothing at all.
 */
class TradeConservationStressTest {

    private static final List<ItemData> ITEMS = List.of(
            TestData.item("DIAMOND", "default", 64),
            TestData.item("EMERALD", "default", 64),
            TestData.item("GOLD_INGOT", "default", 64),
            TestData.item("ARROW", "default", 16),
            TestData.item("DIAMOND_SWORD", "sharp", 1));

    private final TradeEngine engine = new TradeEngine(2_000, false);

    @Test
    void randomizedTradesNeverCreateOrDestroyItems() {
        for (long seed : new long[]{3L, 17L, 512L, 2026L}) {
            runScenario(seed);
        }
    }

    private void runScenario(long seed) {
        Random random = new Random(seed);
        int capacity = 54;
        StockStorage stock = new StockStorage(capacity);
        ProceedsStorage proceeds = new ProceedsStorage();
        List<TradeOffer> offers = new ArrayList<>();

        for (int i = 0; i < 8; i++) {
            TradeMode mode = random.nextBoolean() ? TradeMode.SELL : TradeMode.BUY;
            int paymentTypes = 1 + random.nextInt(3);
            List<PaymentRequirement> payments = new ArrayList<>();
            for (int p = 0; p < paymentTypes; p++) {
                ItemData item = ITEMS.get(random.nextInt(ITEMS.size()));
                payments.add(new PaymentRequirement(item, 1 + random.nextInt(40), random.nextBoolean()));
            }
            ItemData receive = ITEMS.get(random.nextInt(ITEMS.size()));
            int receiveAmount = 1 + random.nextInt(Math.min(16, receive.maxStackSize() * 2));
            long expiresAt = random.nextInt(10) == 0 ? System.currentTimeMillis() - 1 : 0L;
            TradeOffer offer = TradeOffer.create(mode, payments, receive, receiveAmount, expiresAt);
            offers.add(offer);
            if (mode == TradeMode.SELL) {
                // Deep stock so the stress run keeps succeeding instead of
                // exhausting every selling offer after a handful of trades.
                stock.add(receive, receive.maxStackSize() * 3);
            }
        }
        // Guarantee a tradeable baseline for both offer directions.
        for (TradeOffer offer : offers) {
            if (offer.mode() == TradeMode.SELL && stock.count(offer.receiveItem(), true) < offer.receiveAmount()) {
                stock.add(offer.receiveItem(), Math.min(offer.receiveAmount(), offer.receiveItem().maxStackSize()));
            }
        }
        for (ItemData item : ITEMS) {
            stock.add(item, 1 + random.nextInt(item.maxStackSize()));
        }
        for (ItemData item : ITEMS) {
            proceeds.add(item, 1 + random.nextInt(item.maxStackSize()));
        }

        Shop shop = TestData.shop(capacity, stock, proceeds, offers);

        List<InventoryModel> customers = new ArrayList<>();
        for (int c = 0; c < 12; c++) {
            InventoryModel model = InventoryModel.empty(36);
            int stacks = random.nextInt(37);
            for (int s = 0; s < stacks; s++) {
                ItemData item = ITEMS.get(random.nextInt(ITEMS.size()));
                model.add(item, 1 + random.nextInt(item.maxStackSize() * 2));
            }
            customers.add(model);
        }
        // Half of the customers are deliberately well stocked with every item
        // type, so payment checks are exercised across all offers.
        for (int c = 0; c < Math.min(6, customers.size()); c++) {
            InventoryModel model = customers.get(c);
            for (ItemData item : ITEMS) {
                model.add(item, item.maxStackSize() * 2);
            }
        }

        Map<String, Integer> initialLedger = mergeAllTotal(shop, customers);

        long expectedIn = 0;
        long expectedOut = 0;
        int successes = 0;
        for (int i = 0; i < 1_500; i++) {
            InventoryModel customer = customers.get(random.nextInt(customers.size()));
            TradeOffer offer = offers.get(random.nextInt(offers.size()));
            InventoryModel inventoryBefore = customer.copy();
            Map<String, Integer> shopBefore = snapshot(shop);
            Map<String, Integer> customerBefore = snapshot(customer);

            TradeResult result = engine.execute(shop, offer.id(), UUID.randomUUID(), "Stress",
                    customer, TradeCommit.NOOP);

            Map<String, Integer> after = snapshot(shop);
            Map<String, Integer> customerAfter = snapshot(customer);
            if (result.success()) {
                successes++;
                expectedIn += offer.payAmount();
                expectedOut += offer.receiveAmount();
            } else {
                assertEquals(shopBefore, after,
                        "failed trade mutated shop state at iteration " + i + " (seed " + seed + ")");
                assertEquals(customerBefore, customerAfter,
                        "failed trade mutated customer inventory at iteration " + i + " (seed " + seed + ")");
                assertTrue(customer.changedSlots(inventoryBefore).isEmpty(),
                        "failed trade left a partial inventory diff (seed " + seed + ")");
            }

            // Runs for both outcomes: no path may create or destroy a single item.
            assertEquals(initialLedger, mergeAllTotal(shop, customers),
                    "item conservation broken at iteration " + i + " (seed " + seed + ")");
            assertStockInvariants(shop.stock());
            assertProceedsInvariants(shop.proceeds());
        }

        assertTrue(successes > 100, "scenario should exercise many successful trades, got " + successes);
        assertEquals(successes, shop.stats().totalTrades());
        assertEquals(expectedIn, shop.stats().itemsIn());
        assertEquals(expectedOut, shop.stats().itemsOut());
        assertEquals(initialLedger, mergeAllTotal(shop, customers));
    }

    private static Map<String, Integer> mergeAllTotal(Shop shop, List<InventoryModel> customers) {
        Map<String, Integer> total = new HashMap<>();
        collectStock(total, shop.stock());
        collectProceeds(total, shop.proceeds());
        for (InventoryModel customer : customers) {
            collectInventory(total, customer);
        }
        return total;
    }

    private static Map<String, Integer> snapshot(Shop shop) {
        Map<String, Integer> map = new HashMap<>();
        collectStock(map, shop.stock());
        collectProceeds(map, shop.proceeds());
        return map;
    }

    private static Map<String, Integer> snapshot(InventoryModel model) {
        Map<String, Integer> map = new HashMap<>();
        collectInventory(map, model);
        return map;
    }

    private static void collectInventory(Map<String, Integer> map, InventoryModel model) {
        for (InventoryModel.HeldItem held : model.contents()) {
            if (held != null) {
                map.merge(held.item().signature(), held.amount(), Integer::sum);
            }
        }
    }

    private static void collectStock(Map<String, Integer> map, StockStorage stock) {
        stock.stacks().forEach(stack -> map.merge(stack.item().signature(), stack.amount(), Integer::sum));
    }

    private static void collectProceeds(Map<String, Integer> map, ProceedsStorage proceeds) {
        proceeds.stacks().forEach(stack -> map.merge(stack.item().signature(), stack.amount(), Integer::sum));
    }

    private static void assertStockInvariants(StockStorage stock) {
        assertTrue(stock.slotsUsed() <= stock.capacitySlots());
        for (var stack : stock.stacks()) {
            assertNotNull(stack.item());
            assertTrue(stack.amount() >= 1);
            assertTrue(stack.amount() <= stack.item().maxStackSize());
        }
    }

    private static void assertProceedsInvariants(ProceedsStorage proceeds) {
        for (var stack : proceeds.stacks()) {
            assertNotNull(stack.item());
            assertTrue(stack.amount() >= 1);
            assertTrue(stack.amount() <= stack.item().maxStackSize());
        }
        assertTrue(proceeds.totalItems() >= 0);
    }
}

package dev.bartershop.core;

import dev.bartershop.TestData;
import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.core.stock.StockStorage;
import dev.bartershop.core.trade.FailureReason;
import dev.bartershop.core.trade.TradeCommit;
import dev.bartershop.core.trade.TradeEngine;
import dev.bartershop.core.trade.TradePlan;
import dev.bartershop.core.trade.TradeResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeEngineTest {

    private static final ItemData EMERALD = TestData.item("EMERALD");
    private static final ItemData DIAMOND = TestData.item("DIAMOND");
    private static final ItemData COBBLESTONE = TestData.item("COBBLESTONE");

    private final TradeEngine engine = new TradeEngine(2_000, false);

    @Test
    void sellTradeExchangesItemsAndQueuesOwnerProceeds() {
        TradeOffer offer = sellOffer(16, 1);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 2);
        ProceedsStorage proceeds = new ProceedsStorage();
        Shop shop = TestData.shop(27, stock, proceeds, offer);

        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 32);

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER, "Customer",
                customer, TradeCommit.NOOP);

        assertTrue(result.success());
        assertEquals(16, customer.countMatching(EMERALD, true));
        assertEquals(1, customer.countMatching(DIAMOND, true));
        assertEquals(1, shop.stock().count(DIAMOND, true));
        assertEquals(16, shop.proceeds().countOf(EMERALD));
    }

    @Test
    void buyTradeAddsCustomerItemsToStockAndPaysFromStock() {
        TradeOffer offer = buyOffer(32, 1);
        StockStorage stock = new StockStorage(27);
        stock.add(EMERALD, 10);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);

        InventoryModel customer = InventoryModel.empty(36);
        customer.add(COBBLESTONE, 64);

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER, "Customer",
                customer, TradeCommit.NOOP);

        assertTrue(result.success());
        assertEquals(32, customer.countMatching(COBBLESTONE, true));
        assertEquals(1, customer.countMatching(EMERALD, true));
        assertEquals(9, shop.stock().count(EMERALD, true));
        assertEquals(32, shop.stock().count(COBBLESTONE, true));
        assertTrue(shop.proceeds().isEmpty());
    }

    @Test
    void multipleStackQuantitiesAreSupported() {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL, COBBLESTONE, 64, DIAMOND, 3, true);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 3);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(COBBLESTONE, 64);

        assertTrue(engine.execute(shop, offer.id(), TestData.CUSTOMER, "C",
                customer, TradeCommit.NOOP).success());
        assertEquals(3, customer.countMatching(DIAMOND, true));
        assertEquals(64, shop.proceeds().countOf(COBBLESTONE));
    }

    @Test
    void insufficientPaymentLeavesEverythingUntouched() {
        TradeOffer offer = sellOffer(16, 1);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 15);

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER, "C",
                customer, TradeCommit.NOOP);

        assertFalse(result.success());
        assertEquals(FailureReason.INSUFFICIENT_PAYMENT, result.failure());
        assertEquals(15, customer.countMatching(EMERALD, true));
        assertEquals(0, customer.countMatching(DIAMOND, true));
        assertEquals(1, shop.stock().count(DIAMOND, true));
        assertTrue(shop.proceeds().isEmpty());
    }

    @Test
    void insufficientStockLeavesEverythingUntouched() {
        TradeOffer offer = sellOffer(1, 5);
        Shop shop = TestData.shop(27, new StockStorage(27), new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 10);

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER, "C",
                customer, TradeCommit.NOOP);

        assertEquals(FailureReason.OUT_OF_STOCK, result.failure());
        assertEquals(10, customer.countMatching(EMERALD, true));
        assertTrue(shop.proceeds().isEmpty());
    }

    @Test
    void fullCustomerInventoryIsRejectedWithoutChangingAnything() {
        TradeOffer offer = sellOffer(1, 128);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 128);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = TestData.inventory(36, COBBLESTONE, 35);
        customer.set(35, new InventoryModel.HeldItem(EMERALD, 1));

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER, "C",
                customer, TradeCommit.NOOP);

        assertEquals(FailureReason.INVENTORY_FULL, result.failure());
        assertEquals(1, customer.countMatching(EMERALD, true));
        assertEquals(0, customer.countMatching(DIAMOND, true));
        assertEquals(128, shop.stock().count(DIAMOND, true));
    }

    @Test
    void fullShopStorageBlocksBuyOffers() {
        TradeOffer offer = buyOffer(32, 1);
        StockStorage stock = new StockStorage(1);
        stock.add(EMERALD, 64);
        Shop shop = TestData.shop(1, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(COBBLESTONE, 32);

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER, "C",
                customer, TradeCommit.NOOP);

        assertEquals(FailureReason.STORAGE_FULL, result.failure());
        assertEquals(32, customer.countMatching(COBBLESTONE, true));
        assertEquals(64, shop.stock().count(EMERALD, true));
    }

    @Test
    void exactMatchingRejectsDifferentMetadata() {
        ItemData emeraldVariant = TestData.item("EMERALD", "variant", 64);
        TradeOffer offer = TradeOffer.create(TradeMode.SELL, EMERALD, 16, DIAMOND, 1, true);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(emeraldVariant, 16);

        assertEquals(FailureReason.INSUFFICIENT_PAYMENT,
                engine.execute(shop, offer.id(), TestData.CUSTOMER, "C", customer,
                        TradeCommit.NOOP).failure());

        TradeOffer loose = offer.withExactPay(false);
        shop.replaceOffer(loose);
        assertTrue(engine.execute(shop, loose.id(), TestData.CUSTOMER, "C", customer,
                TradeCommit.NOOP).success());
        assertEquals(0, customer.countMatching(emeraldVariant, true));
        assertEquals(16, shop.proceeds().countOf(emeraldVariant));
    }

    @Test
    void stockMustMatchReceiveMetadataExactly() {
        ItemData variant = TestData.item("DIAMOND", "variant", 64);
        TradeOffer offer = TradeOffer.create(TradeMode.SELL, EMERALD, 1, DIAMOND, 1, true);
        StockStorage stock = new StockStorage(27);
        stock.add(variant, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 1);

        assertEquals(FailureReason.OUT_OF_STOCK,
                engine.execute(shop, offer.id(), TestData.CUSTOMER, "C", customer,
                        TradeCommit.NOOP).failure());
    }

    @Test
    void ownerCannotTradeUnlessEnabled() {
        TradeOffer offer = sellOffer(1, 1);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 1);

        assertEquals(FailureReason.OWNER_CANNOT_TRADE,
                engine.execute(shop, offer.id(), TestData.OWNER, "Owner", customer,
                        TradeCommit.NOOP).failure());

        TradeEngine permissive = new TradeEngine(2_000, true);
        assertTrue(permissive.execute(shop, offer.id(), TestData.OWNER, "Owner", customer,
                TradeCommit.NOOP).success());
    }

    @Test
    void disabledAndOrphanedShopsAreRejected() {
        TradeOffer offer = sellOffer(1, 1);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 1);

        shop.setEnabled(false);
        assertEquals(FailureReason.SHOP_DISABLED,
                engine.execute(shop, offer.id(), TestData.CUSTOMER, "C", customer,
                        TradeCommit.NOOP).failure());
        shop.setEnabled(true);
        shop.setOrphaned(true);
        assertEquals(FailureReason.SHOP_ORPHANED,
                engine.execute(shop, offer.id(), TestData.CUSTOMER, "C", customer,
                        TradeCommit.NOOP).failure());
    }

    @Test
    void repeatedClicksOnlyExecuteOnce() {
        TradeOffer offer = sellOffer(1, 1);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 1);

        assertTrue(engine.execute(shop, offer.id(), TestData.CUSTOMER, "C", customer,
                TradeCommit.NOOP).success());
        for (int i = 0; i < 10; i++) {
            assertFalse(engine.execute(shop, offer.id(), TestData.CUSTOMER, "C", customer,
                    TradeCommit.NOOP).success());
        }
        assertEquals(0, customer.countMatching(EMERALD, true));
        assertEquals(1, customer.countMatching(DIAMOND, true));
        assertEquals(0, shop.stock().count(DIAMOND, true));
        assertEquals(1, shop.proceeds().countOf(EMERALD));
    }

    @Test
    void failedCommitRollsBackInventoryAndShop() {
        TradeOffer offer = sellOffer(16, 1);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 2);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 32);

        AtomicBoolean rolledBack = new AtomicBoolean();
        AtomicBoolean aborted = new AtomicBoolean();
        TradeCommit failing = new TradeCommit() {
            @Override
            public void beforeCommit(TradePlan plan) {
            }

            @Override
            public void applyInventory(TradePlan plan) {
            }

            @Override
            public void commit(TradePlan plan) {
                throw new IllegalStateException("simulated database failure");
            }

            @Override
            public void rollbackInventory(TradePlan plan) {
                rolledBack.set(true);
            }

            @Override
            public void abortJournal(TradePlan plan) {
                aborted.set(true);
            }
        };

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER, "C",
                customer, failing);

        assertEquals(FailureReason.INTERNAL_ERROR, result.failure());
        assertTrue(rolledBack.get());
        assertTrue(aborted.get());
        assertEquals(32, customer.countMatching(EMERALD, true));
        assertEquals(0, customer.countMatching(DIAMOND, true));
        assertEquals(2, shop.stock().count(DIAMOND, true));
        assertTrue(shop.proceeds().isEmpty());
    }

    @Test
    void journalFailurePreventsAllMutation() {
        TradeOffer offer = sellOffer(16, 1);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 2);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 32);

        TradeCommit failing = new TradeCommit() {
            @Override
            public void beforeCommit(TradePlan plan) {
                throw new IllegalStateException("journal unavailable");
            }

            @Override
            public void applyInventory(TradePlan plan) {
                throw new AssertionError("should not run");
            }

            @Override
            public void commit(TradePlan plan) {
                throw new AssertionError("should not run");
            }

            @Override
            public void rollbackInventory(TradePlan plan) {
                throw new AssertionError("should not run");
            }

            @Override
            public void abortJournal(TradePlan plan) {
            }
        };

        assertEquals(FailureReason.INTERNAL_ERROR, engine.execute(shop, offer.id(),
                TestData.CUSTOMER, "C", customer, failing).failure());
        assertEquals(32, customer.countMatching(EMERALD, true));
        assertEquals(2, shop.stock().count(DIAMOND, true));
        assertTrue(shop.proceeds().isEmpty());
    }

    @Test
    void concurrentPurchasesCannotOversell() throws Exception {
        TradeOffer offer = sellOffer(1, 1);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 5);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TradeResult>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(executor.submit(() -> {
                InventoryModel customer = InventoryModel.empty(36);
                customer.add(EMERALD, 1);
                start.await(5, TimeUnit.SECONDS);
                return engine.execute(shop, offer.id(), UUID.randomUUID(), "C",
                        customer, TradeCommit.NOOP);
            }));
        }
        start.countDown();
        int successes = 0;
        for (Future<TradeResult> future : futures) {
            if (future.get(10, TimeUnit.SECONDS).success()) {
                successes++;
            }
        }
        executor.shutdownNow();

        assertEquals(5, successes);
        assertEquals(0, shop.stock().count(DIAMOND, true));
        assertEquals(5, shop.proceeds().countOf(EMERALD));
    }

    private TradeOffer sellOffer(int pay, int receive) {
        return TradeOffer.create(TradeMode.SELL, EMERALD, pay, DIAMOND, receive, true);
    }

    private TradeOffer buyOffer(int pay, int receive) {
        return TradeOffer.create(TradeMode.BUY, COBBLESTONE, pay, EMERALD, receive, true);
    }
}

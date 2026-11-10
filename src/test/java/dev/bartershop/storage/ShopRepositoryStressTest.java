package dev.bartershop.storage;

import dev.bartershop.TestData;
import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.LocationKey;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.ShopStats;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.core.stock.StockStorage;
import dev.bartershop.core.trade.TradePlan;
import dev.bartershop.storage.StorageRecords.JournalRecord;
import dev.bartershop.storage.StorageRecords.JournalType;
import dev.bartershop.storage.StorageRecords.LoadedShops;
import dev.bartershop.storage.StorageRecords.TransferRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * High-volume persistence tests: many shops and offers, bulk journal recovery,
 * deletion/transfer conservation and concurrent access through the database
 * thread. Each test uses a disposable database file.
 */
class ShopRepositoryStressTest {

    private static final ItemData DIAMOND = TestData.item("DIAMOND");
    private static final ItemData EMERALD = TestData.item("EMERALD");
    private static final ItemData GOLD = TestData.item("GOLD_INGOT");

    @TempDir
    Path tempDir;

    private Connection connection;
    private ShopRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("stress.db"));
        repository = new ShopRepository(connection);
        repository.migrate();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    @Test
    void dozensOfShopsWithOffersAndStockReloadExactly() throws Exception {
        List<Shop> shops = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            Shop shop = shopAt(i, 27);
            for (int offerIndex = 0; offerIndex < 5; offerIndex++) {
                List<PaymentRequirement> payments = List.of(
                        new PaymentRequirement(EMERALD, 1 + offerIndex, true),
                        new PaymentRequirement(GOLD, 1, offerIndex % 2 == 0));
                shop.addOffer(TradeOffer.create(
                        offerIndex % 2 == 0 ? TradeMode.SELL : TradeMode.BUY,
                        payments, DIAMOND, 1 + offerIndex, 0L));
            }
            shop.stock().add(DIAMOND, 20 + i);
            shop.stock().add(EMERALD, 5 + i);
            shop.proceeds().add(GOLD, 1 + i);
            shop.setStats(new ShopStats(i, i * 10L, i * 5L, i * 1000L, i));
            repository.insertShop(shop);
            shops.add(shop);
        }

        LoadedShops loaded = repository.loadAll();
        assertTrue(loaded.warnings().isEmpty(), loaded.warnings().toString());
        assertEquals(40, loaded.shops().size());
        for (Shop expected : shops) {
            Shop actual = loaded.shops().stream()
                    .filter(shop -> shop.id().equals(expected.id())).findFirst().orElseThrow();
            assertEquals(5, actual.offers().size());
            assertEquals(expected.offers().get(0).payments().size(), actual.offers().get(0).payments().size());
            assertEquals(expected.stock().count(DIAMOND, true), actual.stock().count(DIAMOND, true));
            assertEquals(expected.stock().count(EMERALD, true), actual.stock().count(EMERALD, true));
            assertEquals(expected.proceeds().countOf(GOLD), actual.proceeds().countOf(GOLD));
            assertEquals(expected.stats(), actual.stats());
        }
    }

    @Test
    void repeatedTradesKeepStatisticsAndStateConsistent() throws Exception {
        Shop shop = shopAt(1, 54);
        shop.stock().add(DIAMOND, 200);
        TradeOffer offer = TradeOffer.create(TradeMode.SELL, List.of(
                new PaymentRequirement(EMERALD, 2, true),
                new PaymentRequirement(GOLD, 1, true)), DIAMOND, 1, 0L);
        shop.addOffer(offer);
        repository.insertShop(shop);

        for (int i = 0; i < 120; i++) {
            InventoryModel before = InventoryModel.empty(36);
            before.add(EMERALD, 2);
            before.add(GOLD, 1);
            InventoryModel after = before.copy();
            after.removeMatching(EMERALD, 2, true);
            after.removeMatching(GOLD, 1, true);
            after.add(DIAMOND, 1);

            StockStorage stockAfter = shop.stock().copy();
            assertTrue(stockAfter.remove(DIAMOND, 1, true));
            ProceedsStorage proceedsAfter = shop.proceeds().copy();
            proceedsAfter.add(EMERALD, 2);
            proceedsAfter.add(GOLD, 1);

            TradePlan plan = new TradePlan(shop, offer, UUID.randomUUID(), "Customer",
                    before, after, shop.stock().copy(), stockAfter,
                    shop.proceeds().copy(), proceedsAfter);
            repository.commitTrade(plan);
            shop.setStock(stockAfter);
            shop.setProceeds(proceedsAfter);
        }

        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(80, restored.stock().count(DIAMOND, true));
        assertEquals(240, restored.proceeds().countOf(EMERALD));
        assertEquals(120, restored.proceeds().countOf(GOLD));
        assertEquals(120, restored.stats().totalTrades());
        assertEquals(360, restored.stats().itemsIn());
        assertEquals(120, restored.stats().itemsOut());
        assertTrue(repository.loadJournals().isEmpty());
    }

    @Test
    void bulkInterruptedJournalsRecoverAndLeaveNoResidue() throws Exception {
        Shop shop = shopAt(2, 27);
        repository.insertShop(shop);

        int journals = 25;
        for (int i = 1; i <= journals; i++) {
            StockStorage stock = new StockStorage(27);
            stock.add(DIAMOND, i);
            ProceedsStorage proceeds = new ProceedsStorage();
            proceeds.add(EMERALD, i);
            repository.prepareJournal(new JournalRecord("journal-" + i, JournalType.TRADE, shop.id(),
                    TestData.CUSTOMER,
                    JournalCodec.encodeTrade(stock.stacks(), proceeds.stacks()),
                    System.currentTimeMillis() + i));
        }
        assertEquals(journals, repository.loadJournals().size());
        for (JournalRecord journal : repository.loadJournals()) {
            repository.recoverJournal(journal);
        }
        assertTrue(repository.loadJournals().isEmpty());
        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(journals, restored.stock().count(DIAMOND, true));
        assertEquals(journals, restored.proceeds().countOf(EMERALD));
        assertEquals(journals, repository.loadHistory(shop.id(), 100, 0).stream()
                .filter(entry -> entry.type().equals("RECOVERY")).count());
    }

    @Test
    void deletionAndTransferNeverLoseItems() throws Exception {
        Map<String, Integer> ledger = new HashMap<>();
        List<Shop> shops = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            Shop shop = shopAt(10 + i, 27);
            shop.stock().add(DIAMOND, 10 + i);
            shop.stock().add(EMERALD, i);
            shop.proceeds().add(GOLD, 3);
            repository.insertShop(shop);
            shops.add(shop);
            collect(ledger, shop.stock(), shop.proceeds());
        }

        UUID newOwner = UUID.randomUUID();
        for (int i = 0; i < shops.size(); i++) {
            Shop shop = shops.get(i);
            if (i % 3 == 0) {
                repository.deleteShopToMailbox(shop, "stress delete");
            } else if (i % 3 == 1) {
                repository.transferShop(shop, newOwner, "NewOwner", "Owner");
            }
        }

        Map<String, Integer> after = new HashMap<>();
        for (Shop shop : repository.loadAll().shops()) {
            collect(after, shop.stock(), shop.proceeds());
        }
        ProceedsStorage oldOwnerMailbox = repository.loadMailbox(TestData.OWNER);
        ProceedsStorage newOwnerMailbox = repository.loadMailbox(newOwner);
        for (var stack : oldOwnerMailbox.stacks()) {
            after.merge(stack.item().signature(), stack.amount(), Integer::sum);
        }
        for (var stack : newOwnerMailbox.stacks()) {
            after.merge(stack.item().signature(), stack.amount(), Integer::sum);
        }
        // Transfers keep stock; deletions move stock and proceeds to the mailbox.
        assertEquals(ledger, after);

        List<Shop> remaining = repository.loadAll().shops();
        for (int i = 0; i < shops.size(); i++) {
            UUID id = shops.get(i).id();
            boolean exists = remaining.stream().anyMatch(shop -> shop.id().equals(id));
            if (i % 3 == 0) {
                assertFalse(exists, "deleted shop still loaded: " + id);
            } else {
                assertTrue(exists, "shop should still exist: " + id);
            }
        }
    }

    @Test
    void staleCapacityGrowsInsteadOfDiscardingStock() throws Exception {
        Shop shop = shopAt(3, 27);
        shop.stock().add(DIAMOND, 64);
        shop.stock().add(EMERALD, 32);
        repository.insertShop(shop);

        try (Statement statement = connection.createStatement()) {
            statement.execute("UPDATE shops SET capacity_slots = 1 WHERE id = '" + shop.id() + "'");
        }

        LoadedShops loaded = repository.loadAll();
        assertEquals(1, loaded.shops().size());
        assertFalse(loaded.warnings().isEmpty(), "a capacity correction should be reported");
        Shop restored = loaded.shops().get(0);
        assertEquals(2, restored.capacitySlots());
        assertEquals(2, restored.stock().slotsUsed());
        assertEquals(64, restored.stock().count(DIAMOND, true));
        assertEquals(32, restored.stock().count(EMERALD, true));
    }

    @Test
    void concurrentDatabaseCallsAreSerialisedAndPersist() throws Exception {
        Path file = tempDir.resolve("threaded.db");
        Database database = new Database(file);
        try {
            for (int i = 0; i < 200; i++) {
                StorageRecords.AuditEntry entry = StorageRecords.AuditEntry.create(
                        TestData.OWNER, "Owner", "STRESS", "entry-" + i);
                database.run(repository -> repository.addAudit(entry));
            }
            ExecutorService executor = Executors.newFixedThreadPool(8);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                futures.add(executor.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    int total = 0;
                    for (int j = 0; j < 50; j++) {
                        total += database.call(ShopRepository::countAudit);
                    }
                    return total;
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                assertTrue(future.get(30, TimeUnit.SECONDS) >= 200 * 50 / 8);
            }
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            database.close();
        }

        Database reopened = new Database(file);
        try {
            assertEquals(200, reopened.call(ShopRepository::countAudit));
        } finally {
            reopened.close();
        }
    }

    private static void collect(Map<String, Integer> ledger, StockStorage stock, ProceedsStorage proceeds) {
        for (var stack : stock.stacks()) {
            ledger.merge(stack.item().signature(), stack.amount(), Integer::sum);
        }
        for (var stack : proceeds.stacks()) {
            ledger.merge(stack.item().signature(), stack.amount(), Integer::sum);
        }
    }

    private static Shop shopAt(int index, int capacity) {
        int x = index * 4;
        StockStorage stock = new StockStorage(capacity);
        return Shop.withChestUnits(UUID.randomUUID(), TestData.OWNER, "Owner", "Shop " + index,
                List.of(List.of(new LocationKey("world", x, 64, 0))),
                new LocationKey("world", x, 65, 0), new LocationKey("world", x, 64, -1),
                capacity, true, List.of(), stock, new ProceedsStorage(),
                1000L + index, ShopStats.EMPTY, 8);
    }
}

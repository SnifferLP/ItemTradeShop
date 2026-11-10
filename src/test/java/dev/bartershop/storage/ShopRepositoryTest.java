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
import dev.bartershop.storage.StorageRecords.HistoryEntry;
import dev.bartershop.storage.StorageRecords.JournalRecord;
import dev.bartershop.storage.StorageRecords.JournalType;
import dev.bartershop.storage.StorageRecords.LoadedShops;
import dev.bartershop.storage.StorageRecords.PendingSummary;
import dev.bartershop.storage.StorageRecords.SignClaim;
import dev.bartershop.storage.StorageRecords.TransferRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShopRepositoryTest {

    private static final ItemData DIAMOND = TestData.item("DIAMOND");
    private static final ItemData EMERALD = TestData.item("EMERALD");
    private static final UUID NEW_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @TempDir
    Path tempDir;

    private Connection connection;
    private ShopRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection("jdbc:sqlite:" + tempDir.resolve("test.db"));
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
    void schemaVersionIsWritten() throws Exception {
        try (Statement statement = connection.createStatement();
             var rs = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(rs.next());
            assertEquals(ShopRepository.SCHEMA_VERSION, rs.getInt(1));
        }
    }

    @Test
    void insertAndLoadCompleteShop() throws Exception {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL, EMERALD, 16, DIAMOND, 1, false);
        Shop shop = sampleShop(offer);
        shop.stock().add(DIAMOND, 5);
        shop.proceeds().add(EMERALD, 3);
        repository.insertShop(shop);

        LoadedShops loaded = repository.loadAll();
        assertTrue(loaded.warnings().isEmpty());
        assertEquals(1, loaded.shops().size());
        Shop restored = loaded.shops().get(0);
        assertEquals(shop.id(), restored.id());
        assertEquals(shop.owner(), restored.owner());
        assertEquals(shop.name(), restored.name());
        assertEquals(1, restored.offers().size());
        assertEquals(offer.payItem().signature(), restored.offers().get(0).payItem().signature());
        assertEquals(offer.id(), restored.offers().get(0).id());
        assertEquals(5, restored.stock().count(DIAMOND, true));
        assertEquals(3, restored.proceeds().countOf(EMERALD));
        assertNotNull(restored.sign());
        assertEquals(shop.capacitySlots(), restored.capacitySlots());
    }

    @Test
    void stockAndProceedsUpdatesPersist() throws Exception {
        Shop shop = sampleShop();
        shop.stock().add(DIAMOND, 10);
        repository.insertShop(shop);

        shop.stock().remove(DIAMOND, 4, true);
        shop.stock().add(EMERALD, 7);
        shop.proceeds().add(DIAMOND, 2);
        repository.saveStock(shop);
        repository.saveProceeds(shop);

        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(6, restored.stock().count(DIAMOND, true));
        assertEquals(7, restored.stock().count(EMERALD, true));
        assertEquals(2, restored.proceeds().countOf(DIAMOND));
    }

    @Test
    void tradeCommitAppliesStateHistoryAndRemovesJournal() throws Exception {
        Shop shop = sampleShop(TradeOffer.create(TradeMode.SELL, EMERALD, 16, DIAMOND, 1, true));
        shop.stock().add(DIAMOND, 2);
        repository.insertShop(shop);

        InventoryModel before = InventoryModel.empty(36);
        before.add(EMERALD, 32);
        InventoryModel after = before.copy();
        after.removeMatching(EMERALD, 16, true);
        after.add(DIAMOND, 1);
        StockStorage stockAfter = shop.stock().copy();
        stockAfter.remove(DIAMOND, 1, true);
        ProceedsStorage proceedsAfter = shop.proceeds().copy();
        proceedsAfter.add(EMERALD, 16);
        TradePlan plan = new TradePlan(shop, shop.offers().get(0), TestData.CUSTOMER, "Customer",
                before, after, shop.stock().copy(), stockAfter, shop.proceeds().copy(), proceedsAfter);
        plan.setJournalId("journal-1");
        repository.prepareJournal(new JournalRecord("journal-1", JournalType.TRADE, shop.id(),
                TestData.CUSTOMER, JournalCodec.encodeTrade(stockAfter.stacks(), proceedsAfter.stacks()),
                System.currentTimeMillis()));

        repository.commitTrade(plan);

        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(1, restored.stock().count(DIAMOND, true));
        assertEquals(16, restored.proceeds().countOf(EMERALD));
        assertTrue(repository.loadJournals().isEmpty());
        assertEquals(1, repository.countHistory(shop.id()));
        assertEquals("TRADE", repository.loadHistory(shop.id(), 5, 0).get(0).type());
    }

    @Test
    void interruptedTradeJournalRollsForward() throws Exception {
        Shop shop = sampleShop();
        shop.stock().add(DIAMOND, 2);
        repository.insertShop(shop);

        StockStorage afterStock = shop.stock().copy();
        afterStock.remove(DIAMOND, 1, true);
        ProceedsStorage afterProceeds = shop.proceeds().copy();
        afterProceeds.add(EMERALD, 16);
        String journalId = "journal-recover";
        repository.prepareJournal(new JournalRecord(journalId, JournalType.TRADE, shop.id(),
                TestData.CUSTOMER,
                JournalCodec.encodeTrade(afterStock.stacks(), afterProceeds.stacks()),
                System.currentTimeMillis()));

        String note = repository.recoverJournal(repository.loadJournals().get(0));

        assertTrue(note.contains("rolled forward"));
        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(1, restored.stock().count(DIAMOND, true));
        assertEquals(16, restored.proceeds().countOf(EMERALD));
        assertTrue(repository.loadJournals().isEmpty());
        assertEquals(1, repository.loadHistory(shop.id(), 5, 0).stream()
                .filter(entry -> entry.type().equals("RECOVERY")).count());
    }

    @Test
    void interruptedCollectionJournalReturnsItemsToProceeds() throws Exception {
        Shop shop = sampleShop();
        shop.proceeds().add(DIAMOND, 5);
        repository.insertShop(shop);

        ProceedsStorage before = shop.proceeds().copy();
        ProceedsStorage after = before.copy();
        after.removeAt(0, 2);
        String journalId = "collection-recover";
        JournalRecord journal = new JournalRecord(journalId, JournalType.COLLECTION, shop.id(),
                shop.owner(), JournalCodec.encodeItems(List.of(new dev.bartershop.core.model.StockStack(DIAMOND, 2))),
                System.currentTimeMillis());
        repository.beginCollection(journal, shop, after);
        assertEquals(3, repository.loadAll().shops().get(0).proceeds().countOf(DIAMOND));

        repository.recoverJournal(repository.loadJournals().get(0));

        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(5, restored.proceeds().countOf(DIAMOND));
        assertTrue(repository.loadJournals().isEmpty());
    }

    @Test
    void collectionCanBeRolledBackOrCompleted() throws Exception {
        Shop shop = sampleShop();
        shop.proceeds().add(DIAMOND, 10);
        repository.insertShop(shop);

        ProceedsStorage after = shop.proceeds().copy();
        after.removeAt(0, 6);
        JournalRecord journal = new JournalRecord("collection-1", JournalType.COLLECTION, shop.id(),
                shop.owner(), JournalCodec.encodeItems(List.of()), System.currentTimeMillis());
        repository.beginCollection(journal, shop, after);
        assertEquals(4, repository.loadAll().shops().get(0).proceeds().countOf(DIAMOND));

        repository.rollbackCollection("collection-1", shop.id(), shop.owner(), shop.proceeds());
        assertEquals(10, repository.loadAll().shops().get(0).proceeds().countOf(DIAMOND));
        assertTrue(repository.loadJournals().isEmpty());

        repository.beginCollection(journal, shop, after);
        repository.completeCollection("collection-1", shop.id(), shop.owner(), after,
                HistoryEntry.create(shop.id(), shop.owner(), shop.owner(), "Owner", "COLLECT", "test"));
        assertEquals(4, repository.loadAll().shops().get(0).proceeds().countOf(DIAMOND));
        assertTrue(repository.loadJournals().isEmpty());
    }

    @Test
    void mailboxCollectionUpdatesOnlyMailboxRows() throws Exception {
        Shop shop = sampleShop();
        shop.stock().add(DIAMOND, 5);
        shop.proceeds().add(EMERALD, 3);
        repository.insertShop(shop);
        repository.deleteShopToMailbox(shop, "test delete");

        ProceedsStorage mailbox = repository.loadMailbox(shop.owner());
        assertEquals(5, mailbox.countOf(DIAMOND));
        assertEquals(3, mailbox.countOf(EMERALD));

        ProceedsStorage after = mailbox.copy();
        int diamondIndex = -1;
        for (int i = 0; i < after.stacks().size(); i++) {
            if (after.at(i).item().signature().equals(DIAMOND.signature())) {
                diamondIndex = i;
                break;
            }
        }
        assertTrue(diamondIndex >= 0);
        after.removeAt(diamondIndex, 2);
        JournalRecord journal = new JournalRecord("mail-1", JournalType.COLLECTION, null, shop.owner(),
                JournalCodec.encodeItems(List.of(new dev.bartershop.core.model.StockStack(DIAMOND, 2))),
                System.currentTimeMillis());
        repository.beginMailboxCollection(journal, shop.owner(), after);
        assertEquals(3, repository.loadMailbox(shop.owner()).countOf(DIAMOND));
        repository.rollbackCollection("mail-1", null, shop.owner(), mailbox);
        assertEquals(5, repository.loadMailbox(shop.owner()).countOf(DIAMOND));
    }

    @Test
    void deletingShopPreservesStockAndProceedsInMailbox() throws Exception {
        Shop shop = sampleShop();
        shop.stock().add(DIAMOND, 5);
        shop.proceeds().add(EMERALD, 3);
        repository.insertShop(shop);

        repository.deleteShopToMailbox(shop, "owner deletion");

        assertTrue(repository.loadAll().shops().isEmpty());
        ProceedsStorage mailbox = repository.loadMailbox(shop.owner());
        assertEquals(5, mailbox.countOf(DIAMOND));
        assertEquals(3, mailbox.countOf(EMERALD));
        List<PendingSummary> summaries = repository.findPendingSummaries(shop.owner());
        assertEquals(1, summaries.size());
        assertNull(summaries.get(0).shopId());
        // Labels for the mailbox are localised by the presentation layer.
        assertEquals("", summaries.get(0).label());
        assertEquals(8, summaries.get(0).totalItems());
    }

    @Test
    void transferMovesProceedsToFormerOwnerMailbox() throws Exception {
        Shop shop = sampleShop();
        shop.stock().add(DIAMOND, 5);
        shop.proceeds().add(EMERALD, 3);
        repository.insertShop(shop);

        repository.transferShop(shop, NEW_OWNER, "NewOwner", "Owner");

        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(NEW_OWNER, restored.owner());
        assertEquals("NewOwner", restored.ownerName());
        assertEquals(5, restored.stock().count(DIAMOND, true));
        assertTrue(restored.proceeds().isEmpty());
        assertEquals(3, repository.loadMailbox(shop.owner()).countOf(EMERALD));
        assertTrue(repository.findPendingSummaries(NEW_OWNER).isEmpty());
    }

    @Test
    void concurrentSafeDatabaseWritesUseSingleConnection() throws Exception {
        Shop shop = sampleShop();
        repository.insertShop(shop);
        shop.stock().add(DIAMOND, 1);
        repository.saveStock(shop);
        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(1, restored.stock().count(DIAMOND, true));
    }

    @Test
    void corruptOfferIsSkippedWithWarning() throws Exception {
        Shop shop = sampleShop();
        repository.insertShop(shop);
        try (PreparedStatement ps = connection.prepareStatement("""
                INSERT INTO offers (id, shop_id, sort_order, mode,
                    pay_material, pay_signature, pay_serialized, pay_max_stack, pay_amount,
                    receive_material, receive_signature, receive_serialized, receive_max_stack,
                    receive_amount, exact_pay)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, shop.id().toString());
            ps.setInt(3, 0);
            ps.setString(4, "SELL");
            ps.setString(5, "DIAMOND");
            ps.setString(6, "sig");
            ps.setString(7, "AA==");
            ps.setInt(8, 64);
            ps.setInt(9, 0);
            ps.setString(10, "EMERALD");
            ps.setString(11, "sig2");
            ps.setString(12, "AA==");
            ps.setInt(13, 64);
            ps.setInt(14, 1);
            ps.setInt(15, 1);
            ps.executeUpdate();
        }

        LoadedShops loaded = repository.loadAll();
        assertEquals(1, loaded.shops().size());
        assertTrue(loaded.shops().get(0).offers().isEmpty());
        assertFalse(loaded.warnings().isEmpty());
    }

    @Test
    void historyIsPaginatedNewestFirst() throws Exception {
        Shop shop = sampleShop();
        repository.insertShop(shop);
        for (int i = 0; i < 5; i++) {
            repository.addHistory(HistoryEntry.create(shop.id(), shop.owner(), TestData.CUSTOMER,
                    "Customer", "TRADE", "entry-" + i));
        }
        assertEquals(5, repository.countHistory(shop.id()));
        List<HistoryEntry> page = repository.loadHistory(shop.id(), 2, 0);
        assertEquals(2, page.size());
        assertEquals("entry-4", page.get(0).description());
        assertEquals("entry-3", page.get(1).description());
        assertEquals(1, repository.loadHistory(shop.id(), 2, 4).size());
        assertEquals(5, repository.loadHistoryForOwner(shop.owner(), 10, 0).size());
    }

    @Test
    void pendingSummariesGroupShopAndMailbox() throws Exception {
        Shop shop = sampleShop();
        shop.proceeds().add(EMERALD, 3);
        repository.insertShop(shop);
        repository.addMailboxItems(shop.owner(), List.of(new dev.bartershop.core.model.StockStack(DIAMOND, 2)),
                "test");

        List<PendingSummary> summaries = repository.findPendingSummaries(shop.owner());
        assertEquals(2, summaries.size());
        assertTrue(summaries.stream().anyMatch(summary -> summary.shopId() != null
                && summary.totalItems() == 3));
        assertTrue(summaries.stream().anyMatch(summary -> summary.shopId() == null
                && summary.totalItems() == 2));
    }

    @Test
    void signClaimsPersistAndCanBeRemoved() throws Exception {
        LocationKey sign = new LocationKey("world", 1, 2, 3);
        repository.saveClaim(new SignClaim(sign, TestData.OWNER, "Owner", 123L));
        assertEquals(1, repository.loadClaims().size());
        repository.deleteClaim(sign);
        assertTrue(repository.loadClaims().isEmpty());
    }

    @Test
    void auditLogRoundTrips() throws Exception {
        repository.addAudit(StorageRecords.AuditEntry.create(TestData.OWNER, "Owner",
                "TEST", "details"));
        assertEquals(1, repository.countAudit());
        assertEquals("TEST", repository.loadAudit(10, 0).get(0).action());
    }

    @Test
    void multiChestLayoutAndCapacityRoundTrip() throws Exception {
        StockStorage stock = new StockStorage(81);
        Shop shop = Shop.withChestUnits(UUID.randomUUID(), TestData.OWNER, "Owner", "Multi",
                List.of(
                        List.of(new LocationKey("world", 1, 64, 1), new LocationKey("world", 2, 64, 1)),
                        List.of(new LocationKey("world", 5, 64, 5))),
                new LocationKey("world", 1, 65, 1), new LocationKey("world", 1, 64, 0),
                81, true, List.of(), stock, new ProceedsStorage(), System.currentTimeMillis(),
                new ShopStats(4, 10, 8, 1234, 2), 12);
        repository.insertShop(shop);

        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(2, restored.chestUnitCount());
        assertEquals(3, restored.chestBlocks().size());
        assertEquals(81, restored.capacitySlots());
        assertEquals(81, restored.stock().capacitySlots());
        assertEquals(new ShopStats(4, 10, 8, 1234, 2), restored.stats());
        assertEquals(12, restored.warningThreshold());
    }

    @Test
    void multiplePaymentRequirementsRoundTrip() throws Exception {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL,
                List.of(new PaymentRequirement(EMERALD, 12, true),
                        new PaymentRequirement(DIAMOND, 3, false)),
                DIAMOND, 1, System.currentTimeMillis() + 3_600_000L);
        Shop shop = sampleShop(offer);
        repository.insertShop(shop);

        TradeOffer restored = repository.loadAll().shops().get(0).offers().get(0);
        assertEquals(2, restored.payments().size());
        assertEquals(12, restored.payments().get(0).amount());
        assertEquals(DIAMOND.signature(), restored.payments().get(1).item().signature());
        assertFalse(restored.payments().get(1).exact());
        assertTrue(restored.expiresAt() > System.currentTimeMillis());
    }

    @Test
    void tradeCommitUpdatesPersistentStatisticsAndUniqueCustomers() throws Exception {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL, EMERALD, 4, DIAMOND, 1, true);
        Shop shop = sampleShop(offer);
        shop.stock().add(DIAMOND, 5);
        repository.insertShop(shop);

        commitTestTrade(shop, offer, TestData.CUSTOMER, "Customer");
        commitTestTrade(shop, offer, TestData.CUSTOMER, "Customer");
        UUID secondCustomer = UUID.fromString("00000000-0000-0000-0000-000000000099");
        commitTestTrade(shop, offer, secondCustomer, "Second");

        Shop restored = repository.loadAll().shops().get(0);
        assertEquals(3, restored.stats().totalTrades());
        assertEquals(12, restored.stats().itemsIn());
        assertEquals(3, restored.stats().itemsOut());
        assertEquals(2, restored.stats().uniqueCustomers());
        assertTrue(restored.stats().lastTradeAt() > 0);
    }

    @Test
    void transferRequestsPersistAndAreConsumedByTransfer() throws Exception {
        Shop shop = sampleShop();
        repository.insertShop(shop);
        TransferRequest request = new TransferRequest(UUID.randomUUID(), shop.id(), shop.name(),
                shop.owner(), shop.ownerName(), NEW_OWNER, "NewOwner",
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000);
        repository.replaceTransferRequest(request);

        assertEquals(request.id(), repository.loadTransferRequestForShop(shop.id()).orElseThrow().id());
        assertEquals(1, repository.loadTransferRequestsFor(NEW_OWNER).size());

        repository.transferShop(shop, NEW_OWNER, "NewOwner", "Owner");
        assertTrue(repository.loadTransferRequest(request.id()).isEmpty());
        assertEquals(NEW_OWNER, repository.loadAll().shops().get(0).owner());
    }

    private void commitTestTrade(Shop shop, TradeOffer offer, UUID customerId, String customerName)
            throws Exception {
        InventoryModel before = InventoryModel.empty(36);
        before.add(EMERALD, 4);
        InventoryModel after = before.copy();
        after.removeMatching(EMERALD, 4, true);
        after.add(DIAMOND, 1);
        StockStorage stockAfter = shop.stock().copy();
        stockAfter.remove(DIAMOND, 1, true);
        ProceedsStorage proceedsAfter = shop.proceeds().copy();
        proceedsAfter.add(EMERALD, 4);
        TradePlan plan = new TradePlan(shop, offer, customerId, customerName, before, after,
                shop.stock().copy(), stockAfter, shop.proceeds().copy(), proceedsAfter);
        repository.commitTrade(plan);
        shop.setStock(stockAfter);
        shop.setProceeds(proceedsAfter);
    }

    private Shop sampleShop(TradeOffer... offers) {
        StockStorage stock = new StockStorage(27);
        ProceedsStorage proceeds = new ProceedsStorage();
        return TestData.shop(27, stock, proceeds, offers);
    }
}

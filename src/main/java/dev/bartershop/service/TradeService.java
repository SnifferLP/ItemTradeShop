package dev.bartershop.service;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.config.Settings;
import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.trade.FailureReason;
import dev.bartershop.core.trade.TradeCommit;
import dev.bartershop.core.trade.TradeEngine;
import dev.bartershop.core.trade.TradePlan;
import dev.bartershop.core.trade.TradeResult;
import dev.bartershop.storage.Database;
import dev.bartershop.storage.JournalCodec;
import dev.bartershop.storage.StorageRecords.JournalRecord;
import dev.bartershop.storage.StorageRecords.JournalType;
import dev.bartershop.util.PlayerInventoryAdapter;
import dev.bartershop.util.RateLimiter;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes customer trades on the server thread. Creates the recovery journal
 * before touching inventories, applies the exact planned diff, commits the
 * database state and rolls everything back if any stage fails.
 */
public final class TradeService {

    private final BarterShopPlugin plugin;
    private final Database database;
    private final Settings settings;
    private final Messages messages;
    private final TradeEngine engine;
    private final RateLimiter limiter = new RateLimiter();
    private final ConcurrentHashMap<UUID, AtomicBoolean> inFlight = new ConcurrentHashMap<>();

    public TradeService(BarterShopPlugin plugin, Database database, Settings settings, Messages messages) {
        this.plugin = plugin;
        this.database = database;
        this.settings = settings;
        this.messages = messages;
        this.engine = new TradeEngine(settings.lockTimeoutMillis(), settings.allowOwnerTrade(),
                plugin::maintenanceMode);
    }

    public TradeResult trade(Player customer, Shop shop, UUID offerId) {
        if (!plugin.isMainThread()) {
            throw new IllegalStateException("trades must run on the server thread");
        }
        if (plugin.maintenanceMode()) {
            messages.send(customer, "trade.failed.maintenance");
            return TradeResult.failure(FailureReason.MAINTENANCE);
        }
        if (!customer.hasPermission("bartershop.use")) {
            messages.send(customer, "general.no-permission");
            return TradeResult.failure(FailureReason.INTERNAL_ERROR);
        }
        if (!limiter.allow(customer.getUniqueId(), "trade",
                settings.maxTradesPerMinute(), 60_000L)) {
            messages.send(customer, "error.rate-limit");
            return TradeResult.failure(FailureReason.BUSY);
        }
        if (!ShopManager.canTradeInGameMode(customer.getGameMode(), settings)) {
            messages.send(customer, "trade.failed.gamemode");
            return TradeResult.failure(FailureReason.INTERNAL_ERROR);
        }
        if (!customer.isOnline() || customer.isDead()) {
            return TradeResult.failure(FailureReason.INTERNAL_ERROR);
        }
        if (!PlayerInventoryAdapter.cursorEmpty(customer)) {
            messages.send(customer, "trade.failed.cursor");
            return TradeResult.failure(FailureReason.INVENTORY_FULL);
        }
        AtomicBoolean guard = inFlight.computeIfAbsent(customer.getUniqueId(), key -> new AtomicBoolean());
        if (!guard.compareAndSet(false, true)) {
            messages.send(customer, "trade.failed.busy");
            return TradeResult.failure(FailureReason.BUSY);
        }
        try {
            InventoryModel inventory = PlayerInventoryAdapter.modelOf(customer);
            JournalCommit commit = new JournalCommit(customer);
            TradeResult result = engine.execute(
                    shop,
                    offerId,
                    customer.getUniqueId(),
                    customer.getName(),
                    inventory,
                    commit);
            if (result.success()) {
                TradeOffer offer = result.plan().offer();
                messages.send(customer, "trade.success",
                        "pay", offer.paymentSummary(),
                        "receive", offer.receiveAmount() + "x " + offer.receiveName());
                plugin.shopManager().checkStockWarnings(shop);
                plugin.shopManager().refreshSign(shop);
            } else {
                sendFailure(customer, result.failure());
            }
            return result;
        } finally {
            guard.set(false);
        }
    }

    /** Drops the per-player in-flight guard when a player leaves. */
    public void forget(UUID player) {
        inFlight.remove(player);
    }

    private void sendFailure(Player player, FailureReason reason) {
        String key = switch (reason) {
            case SHOP_DISABLED -> "trade.failed.disabled";
            case SHOP_ORPHANED -> "trade.failed.orphaned";
            case OFFER_MISSING -> "trade.failed.offer-missing";
            case OFFER_EXPIRED -> "trade.failed.expired";
            case OWNER_CANNOT_TRADE -> "trade.failed.owner";
            case MAINTENANCE -> "trade.failed.maintenance";
            case INSUFFICIENT_PAYMENT -> "trade.failed.payment";
            case INVENTORY_FULL -> "trade.failed.inventory-full";
            case OUT_OF_STOCK -> "trade.failed.out-of-stock";
            case STORAGE_FULL -> "trade.failed.storage-full";
            case BUSY -> "trade.failed.busy";
            case INTERNAL_ERROR -> "trade.failed.internal";
        };
        messages.send(player, key);
        if (reason == FailureReason.INTERNAL_ERROR) {
            plugin.getLogger().warning("Trade failed internally for " + player.getName());
        }
    }

    private final class JournalCommit implements TradeCommit {

        private final Player player;

        private JournalCommit(Player player) {
            this.player = player;
        }

        @Override
        public void beforeCommit(TradePlan plan) {
            String journalId = UUID.randomUUID().toString();
            String payload = JournalCodec.encodeTrade(
                    plan.stockAfter().stacks(), plan.proceedsAfter().stacks());
            JournalRecord journal = new JournalRecord(
                    journalId,
                    JournalType.TRADE,
                    plan.shop().id(),
                    plan.customerId(),
                    payload,
                    System.currentTimeMillis());
            database.run(repository -> repository.prepareJournal(journal));
            plan.setJournalId(journalId);
        }

        @Override
        public void applyInventory(TradePlan plan) {
            PlayerInventoryAdapter.apply(player, plan.inventoryBefore(), plan.inventoryAfter());
        }

        @Override
        public void commit(TradePlan plan) {
            database.run(repository -> repository.commitTrade(plan));
        }

        @Override
        public void rollbackInventory(TradePlan plan) {
            try {
                PlayerInventoryAdapter.restore(player, plan.inventoryBefore(), plan.inventoryAfter());
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Could not roll back inventory for " + player.getName()
                        + ": " + e.getMessage());
            }
        }

        @Override
        public void abortJournal(TradePlan plan) {
            if (plan.journalId() == null) {
                return;
            }
            try {
                database.run(repository -> repository.abortJournal(plan.journalId()));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not delete trade journal " + plan.journalId()
                        + ": " + e.getMessage());
            }
        }
    }
}

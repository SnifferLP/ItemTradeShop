package dev.bartershop.service;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.StockStack;
import dev.bartershop.core.proceeds.CollectionPlan;
import dev.bartershop.core.proceeds.ProceedsCollector;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.storage.Database;
import dev.bartershop.storage.JournalCodec;
import dev.bartershop.storage.ShopRepository;
import dev.bartershop.storage.StorageRecords.HistoryEntry;
import dev.bartershop.storage.StorageRecords.JournalRecord;
import dev.bartershop.storage.StorageRecords.JournalType;
import dev.bartershop.util.PlayerInventoryAdapter;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Moves pending proceeds into an owner's inventory. The database is updated
 * (and a recovery journal written) before items are delivered, so a crash
 * cannot leave items both available and delivered by design; a crash in the
 * short delivery window is recovered conservatively by returning the journaled
 * items to pending proceeds on startup.
 */
public final class ProceedsService {

    public enum CollectionOutcome {
        SUCCESS,
        NOTHING_PENDING,
        INVENTORY_FULL,
        NOT_OWNER,
        BUSY,
        CHANGED,
        DATABASE_ERROR
    }

    public record CollectionResult(CollectionOutcome outcome, int itemsCollected, int remaining) {
        public static CollectionResult failure(CollectionOutcome outcome) {
            return new CollectionResult(outcome, 0, 0);
        }
    }

    private final BarterShopPlugin plugin;
    private final Database database;
    private final Messages messages;
    private final ConcurrentHashMap<UUID, ReentrantLock> mailboxLocks = new ConcurrentHashMap<>();

    public ProceedsService(BarterShopPlugin plugin, Database database, Messages messages) {
        this.plugin = plugin;
        this.database = database;
        this.messages = messages;
    }

    public CollectionResult collectShop(Player player, Shop shop) {
        return collectShop(player, shop, true);
    }

    private CollectionResult collectShop(Player player, Shop shop, boolean notify) {
        if (!plugin.isMainThread()) {
            throw new IllegalStateException("collection must run on the server thread");
        }
        if (!shop.owner().equals(player.getUniqueId())) {
            return CollectionResult.failure(CollectionOutcome.NOT_OWNER);
        }
        if (!PlayerInventoryAdapter.cursorEmpty(player)) {
            messages.send(player, "trade.failed.cursor");
            return CollectionResult.failure(CollectionOutcome.BUSY);
        }
        ReentrantLock lock = shop.lock();
        boolean locked;
        try {
            locked = lock.tryLock(plugin.settings().lockTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            locked = false;
        }
        if (!locked) {
            return CollectionResult.failure(CollectionOutcome.BUSY);
        }
        try {
            InventoryModel inventory = PlayerInventoryAdapter.modelOf(player);
            CollectionPlan plan = ProceedsCollector.plan(shop.proceeds(), inventory);
            if (plan.isEmpty()) {
                return CollectionResult.failure(shop.proceeds().isEmpty()
                        ? CollectionOutcome.NOTHING_PENDING : CollectionOutcome.INVENTORY_FULL);
            }
            String journalId = UUID.randomUUID().toString();
            JournalRecord journal = new JournalRecord(
                    journalId, JournalType.COLLECTION, shop.id(), player.getUniqueId(),
                    JournalCodec.encodeItems(plan.collected()), System.currentTimeMillis());
            String history = "Collected " + plan.collectedItems() + " item(s) from pending proceeds";
            try {
                database.run(repository -> repository.beginCollection(journal, shop, plan.proceedsAfter()));
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Could not start collection for shop " + shop.id() + ": " + e.getMessage());
                return CollectionResult.failure(CollectionOutcome.DATABASE_ERROR);
            }
            ProceedsStorage before = plan.proceedsBefore();
            shop.setProceeds(plan.proceedsAfter());
            try {
                PlayerInventoryAdapter.apply(player, plan.inventoryBefore(), plan.inventoryAfter());
            } catch (RuntimeException e) {
                shop.setProceeds(before);
                try {
                    database.run(repository -> repository.rollbackCollection(
                            journalId, shop.id(), player.getUniqueId(), before));
                } catch (RuntimeException rollbackFailure) {
                    plugin.getLogger().severe("Collection rollback failed for shop " + shop.id()
                            + ": " + rollbackFailure.getMessage());
                }
                PlayerInventoryAdapter.restore(player, plan.inventoryBefore(), plan.inventoryAfter());
                return CollectionResult.failure(CollectionOutcome.CHANGED);
            }
            try {
                database.run(repository -> repository.completeCollection(
                        journalId,
                        shop.id(),
                        player.getUniqueId(),
                        plan.proceedsAfter(),
                        HistoryEntry.create(shop.id(), shop.owner(), player.getUniqueId(),
                                player.getName(), "COLLECT", history)));
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Could not finalize collection for shop " + shop.id()
                        + "; reverting delivery: " + e.getMessage());
                PlayerInventoryAdapter.restore(player, plan.inventoryBefore(), plan.inventoryAfter());
                shop.setProceeds(before);
                try {
                    database.run(repository -> repository.rollbackCollection(
                            journalId, shop.id(), player.getUniqueId(), before));
                } catch (RuntimeException rollbackFailure) {
                    plugin.getLogger().severe("Collection journal left for startup recovery: "
                            + rollbackFailure.getMessage());
                }
                return CollectionResult.failure(CollectionOutcome.DATABASE_ERROR);
            }
            if (notify) {
                playCollect(player);
                messages.send(player, "proceeds.collected",
                        "amount", String.valueOf(plan.collectedItems()),
                        "remaining", String.valueOf(plan.proceedsAfter().totalItems()));
            }
            return new CollectionResult(CollectionOutcome.SUCCESS, plan.collectedItems(),
                    plan.proceedsAfter().totalItems());
        } finally {
            lock.unlock();
        }
    }

    public record CollectAllResult(int collected, int entries, CollectionOutcome outcome) {
    }

    /**
     * Collects available proceeds from every owned shop and the recovery
     * mailbox in a stable order. Each shop still uses its own lock and journal,
     * so running this twice or concurrently cannot deliver the same items twice.
     * Per-shop spam is suppressed; one aggregate result is reported instead.
     */
    public CollectAllResult collectAll(Player player) {
        if (!plugin.isMainThread()) {
            throw new IllegalStateException("collection must run on the server thread");
        }
        int collected = 0;
        int entries = 0;
        CollectionOutcome outcome = CollectionOutcome.NOTHING_PENDING;
        // Checked once up front so a carried stack does not stop the sweep halfway.
        if (!PlayerInventoryAdapter.cursorEmpty(player)) {
            messages.send(player, "trade.failed.cursor");
            return new CollectAllResult(0, 0, CollectionOutcome.BUSY);
        }
        for (Shop shop : plugin.shopManager().ownedBy(player.getUniqueId())) {
            if (shop.proceeds().isEmpty()) {
                continue;
            }
            CollectionResult result = collectShop(player, shop, false);
            if (result.outcome() == CollectionOutcome.SUCCESS) {
                collected += result.itemsCollected();
                entries++;
                outcome = CollectionOutcome.SUCCESS;
                continue;
            }
            if (result.outcome() != CollectionOutcome.NOTHING_PENDING) {
                outcome = result.outcome();
                break;
            }
        }
        if (outcome != CollectionOutcome.INVENTORY_FULL && outcome != CollectionOutcome.BUSY
                && outcome != CollectionOutcome.CHANGED && outcome != CollectionOutcome.DATABASE_ERROR) {
            CollectionResult mailbox = collectMailbox(player, false);
            if (mailbox.outcome() == CollectionOutcome.SUCCESS) {
                collected += mailbox.itemsCollected();
                entries++;
                outcome = CollectionOutcome.SUCCESS;
            } else if (mailbox.outcome() != CollectionOutcome.NOTHING_PENDING) {
                outcome = mailbox.outcome();
            }
        }
        if (collected > 0) {
            playCollect(player);
        }
        reportCollectAll(player, collected, entries, outcome);
        return new CollectAllResult(collected, entries, outcome);
    }

    private void reportCollectAll(Player player, int collected, int entries, CollectionOutcome outcome) {
        if (outcome == CollectionOutcome.DATABASE_ERROR) {
            messages.send(player, "error.database");
            return;
        }
        if (outcome == CollectionOutcome.CHANGED) {
            messages.send(player, "error.inventory-changed");
            return;
        }
        if (outcome == CollectionOutcome.BUSY) {
            if (collected > 0) {
                messages.send(player, "proceeds.collected-all",
                        "amount", String.valueOf(collected), "entries", String.valueOf(entries));
            }
            messages.send(player, "proceeds.busy");
            return;
        }
        if (collected <= 0) {
            messages.send(player, outcome == CollectionOutcome.INVENTORY_FULL
                    ? "proceeds.inventory-full" : "proceeds.nothing");
            return;
        }
        messages.send(player, outcome == CollectionOutcome.INVENTORY_FULL
                        ? "proceeds.collected-all-partial" : "proceeds.collected-all",
                "amount", String.valueOf(collected), "entries", String.valueOf(entries));
    }

    public CollectionResult collectMailbox(Player player) {
        return collectMailbox(player, true);
    }

    private CollectionResult collectMailbox(Player player, boolean notify) {
        if (!plugin.isMainThread()) {
            throw new IllegalStateException("collection must run on the server thread");
        }
        if (!PlayerInventoryAdapter.cursorEmpty(player)) {
            messages.send(player, "trade.failed.cursor");
            return CollectionResult.failure(CollectionOutcome.BUSY);
        }
        ReentrantLock lock = mailboxLocks.computeIfAbsent(player.getUniqueId(), key -> new ReentrantLock());
        if (!lock.tryLock()) {
            return CollectionResult.failure(CollectionOutcome.BUSY);
        }
        try {
            ProceedsStorage before;
            try {
                before = database.call(repository -> repository.loadMailbox(player.getUniqueId()));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not load mailbox for " + player.getName() + ": " + e.getMessage());
                return CollectionResult.failure(CollectionOutcome.DATABASE_ERROR);
            }
            InventoryModel inventory = PlayerInventoryAdapter.modelOf(player);
            CollectionPlan plan = ProceedsCollector.plan(before, inventory);
            if (plan.isEmpty()) {
                return CollectionResult.failure(before.isEmpty()
                        ? CollectionOutcome.NOTHING_PENDING : CollectionOutcome.INVENTORY_FULL);
            }
            String journalId = UUID.randomUUID().toString();
            JournalRecord journal = new JournalRecord(
                    journalId, JournalType.COLLECTION, null, player.getUniqueId(),
                    JournalCodec.encodeItems(plan.collected()), System.currentTimeMillis());
            try {
                database.run(repository -> repository.beginMailboxCollection(
                        journal, player.getUniqueId(), plan.proceedsAfter()));
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Could not start mailbox collection for " + player.getName()
                        + ": " + e.getMessage());
                return CollectionResult.failure(CollectionOutcome.DATABASE_ERROR);
            }
            try {
                PlayerInventoryAdapter.apply(player, plan.inventoryBefore(), plan.inventoryAfter());
            } catch (RuntimeException e) {
                PlayerInventoryAdapter.restore(player, plan.inventoryBefore(), plan.inventoryAfter());
                try {
                    database.run(repository -> repository.rollbackCollection(
                            journalId, null, player.getUniqueId(), before));
                } catch (RuntimeException rollbackFailure) {
                    plugin.getLogger().severe("Mailbox collection rollback failed: " + rollbackFailure.getMessage());
                }
                return CollectionResult.failure(CollectionOutcome.CHANGED);
            }
            try {
                database.run(repository -> repository.completeCollection(
                        journalId,
                        null,
                        player.getUniqueId(),
                        plan.proceedsAfter(),
                        HistoryEntry.create(null, player.getUniqueId(), player.getUniqueId(),
                                player.getName(), "COLLECT",
                                "Collected " + plan.collectedItems() + " recovered item(s)")));
            } catch (RuntimeException e) {
                plugin.getLogger().severe("Could not finalize mailbox collection for " + player.getName()
                        + ": " + e.getMessage());
                PlayerInventoryAdapter.restore(player, plan.inventoryBefore(), plan.inventoryAfter());
                try {
                    database.run(repository -> repository.rollbackCollection(
                            journalId, null, player.getUniqueId(), before));
                } catch (RuntimeException rollbackFailure) {
                    plugin.getLogger().severe("Mailbox collection journal left for recovery: "
                            + rollbackFailure.getMessage());
                }
                return CollectionResult.failure(CollectionOutcome.DATABASE_ERROR);
            }
            if (notify) {
                playCollect(player);
                messages.send(player, "proceeds.collected",
                        "amount", String.valueOf(plan.collectedItems()),
                        "remaining", String.valueOf(plan.proceedsAfter().totalItems()));
            }
            return new CollectionResult(CollectionOutcome.SUCCESS, plan.collectedItems(),
                    plan.proceedsAfter().totalItems());
        } finally {
            lock.unlock();
        }
    }

    private static void playCollect(Player player) {
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.7f, 1.4f);
    }

    /** Drops per-player lock state when a player leaves; the service creates it lazily. */
    public void forget(UUID player) {
        mailboxLocks.remove(player);
    }

    /** Summary lines for the join notification. */
    public static String itemsSummary(List<StockStack> stacks, int maxTypes,
                                      java.util.function.IntFunction<String> more) {
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (StockStack stack : stacks) {
            if (shown >= maxTypes) {
                out.append(more.apply(stacks.size() - shown));
                break;
            }
            if (shown > 0) {
                out.append(", ");
            }
            out.append(stack.amount()).append("x ").append(stack.item().prettyName());
            shown++;
        }
        return out.toString();
    }
}

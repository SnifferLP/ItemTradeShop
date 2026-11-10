package dev.bartershop.core.trade;

import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.core.stock.StockStorage;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * Executes a single offer atomically.
 *
 * <ol>
 *   <li>Acquire the shop lock so two customers can never oversell shared stock.</li>
 *   <li>Re-validate the shop, offer, payment, delivery capacity and stock against
 *       the live state (never trust a GUI snapshot).</li>
 *   <li>Build a reversible {@link TradePlan}.</li>
 *   <li>Write the recovery journal before anything is mutated.</li>
 *   <li>Apply the player inventory diff, then the shop state, then the durable commit.</li>
 *   <li>Roll back every applied stage if a later stage fails.</li>
 * </ol>
 */
public final class TradeEngine {

    private final long lockTimeoutMillis;
    private final boolean allowOwnerTrade;
    private final BooleanSupplier maintenance;

    public TradeEngine(long lockTimeoutMillis, boolean allowOwnerTrade) {
        this(lockTimeoutMillis, allowOwnerTrade, () -> false);
    }

    public TradeEngine(long lockTimeoutMillis, boolean allowOwnerTrade, BooleanSupplier maintenance) {
        this.lockTimeoutMillis = Math.max(1, lockTimeoutMillis);
        this.allowOwnerTrade = allowOwnerTrade;
        this.maintenance = Objects.requireNonNull(maintenance, "maintenance");
    }

    public TradeResult execute(Shop shop,
                               UUID offerId,
                               UUID customerId,
                               String customerName,
                               InventoryModel inventory,
                               TradeCommit commit) {
        Objects.requireNonNull(shop, "shop");
        Objects.requireNonNull(offerId, "offerId");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(commit, "commit");

        ReentrantLock lock = shop.lock();
        boolean locked;
        try {
            locked = lock.tryLock(lockTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TradeResult.failure(FailureReason.BUSY);
        }
        if (!locked) {
            return TradeResult.failure(FailureReason.BUSY);
        }
        try {
            if (maintenance.getAsBoolean()) {
                return TradeResult.failure(FailureReason.MAINTENANCE);
            }
            if (!shop.enabled()) {
                return TradeResult.failure(FailureReason.SHOP_DISABLED);
            }
            if (shop.orphaned()) {
                return TradeResult.failure(FailureReason.SHOP_ORPHANED);
            }
            Optional<TradeOffer> found = shop.findOffer(offerId);
            if (found.isEmpty()) {
                return TradeResult.failure(FailureReason.OFFER_MISSING);
            }
            TradeOffer offer = found.get();
            if (offer.isExpired(System.currentTimeMillis())) {
                return TradeResult.failure(FailureReason.OFFER_EXPIRED);
            }
            if (!allowOwnerTrade && shop.owner().equals(customerId)) {
                return TradeResult.failure(FailureReason.OWNER_CANNOT_TRADE);
            }

            InventoryModel after = inventory.copy();
            java.util.List<dev.bartershop.core.model.StockStack> payment = new java.util.ArrayList<>();
            for (PaymentRequirement requirement : offer.payments()) {
                java.util.List<dev.bartershop.core.model.StockStack> taken = after.removeMatchingCollect(
                        requirement.item(), requirement.amount(), requirement.exact());
                if (taken.isEmpty()) {
                    return TradeResult.failure(FailureReason.INSUFFICIENT_PAYMENT);
                }
                payment.addAll(taken);
            }
            if (after.add(offer.receiveItem(), offer.receiveAmount()) > 0) {
                return TradeResult.failure(FailureReason.INVENTORY_FULL);
            }

            StockStorage stockAfter = shop.stock().copy();
            if (!stockAfter.remove(offer.receiveItem(), offer.receiveAmount(), true)) {
                return TradeResult.failure(FailureReason.OUT_OF_STOCK);
            }

            ProceedsStorage proceedsAfter = shop.proceeds().copy();
            if (offer.mode() == dev.bartershop.core.model.TradeMode.BUY) {
                for (dev.bartershop.core.model.StockStack paid : payment) {
                    if (stockAfter.add(paid.item(), paid.amount()) > 0) {
                        return TradeResult.failure(FailureReason.STORAGE_FULL);
                    }
                }
            } else {
                for (dev.bartershop.core.model.StockStack paid : payment) {
                    proceedsAfter.add(paid.item(), paid.amount());
                }
            }

            TradePlan plan = new TradePlan(
                    shop,
                    offer,
                    customerId,
                    customerName,
                    inventory.copy(),
                    after,
                    shop.stock().copy(),
                    stockAfter,
                    shop.proceeds().copy(),
                    proceedsAfter);

            try {
                commit.beforeCommit(plan);
            } catch (Exception e) {
                abort(commit, plan);
                return TradeResult.failure(FailureReason.INTERNAL_ERROR);
            }

            boolean inventoryModelApplied = false;
            boolean realInventoryApplied = false;
            boolean shopApplied = false;
            try {
                plan.applyInventory(inventory);
                inventoryModelApplied = true;
                commit.applyInventory(plan);
                realInventoryApplied = true;
                plan.applyShop();
                shopApplied = true;
                commit.commit(plan);
                shop.setStats(shop.stats().afterTrade(
                        plan.paymentItemCount(), plan.deliveredItemCount(),
                        plan.timestampMillis(), plan.newCustomer()));
                return TradeResult.success(plan);
            } catch (Exception e) {
                if (realInventoryApplied) {
                    try {
                        commit.rollbackInventory(plan);
                    } catch (Exception ignored) {
                        // The plugin adapter logs this; the journal remains for recovery.
                    }
                }
                if (inventoryModelApplied) {
                    plan.revertInventory(inventory);
                }
                if (shopApplied) {
                    plan.revertShop();
                }
                abort(commit, plan);
                return TradeResult.failure(FailureReason.INTERNAL_ERROR);
            }
        } finally {
            lock.unlock();
        }
    }

    private static void abort(TradeCommit commit, TradePlan plan) {
        try {
            commit.abortJournal(plan);
        } catch (Exception ignored) {
            // Best effort; the recovery journal is designed to be safe to replay.
        }
    }
}

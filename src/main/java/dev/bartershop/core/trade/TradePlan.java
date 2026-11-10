package dev.bartershop.core.trade;

import dev.bartershop.core.inv.InventoryModel;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeOffer;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.core.stock.StockStorage;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A fully validated trade that has not yet been committed. Holds the exact
 * before/after snapshots of every mutated component so a failure at any point
 * can be rolled back without item loss or duplication.
 */
public final class TradePlan {

    private final Shop shop;
    private final TradeOffer offer;
    private final UUID customerId;
    private final String customerName;
    private final InventoryModel inventoryBefore;
    private final InventoryModel inventoryAfter;
    private final StockStorage stockBefore;
    private final StockStorage stockAfter;
    private final ProceedsStorage proceedsBefore;
    private final ProceedsStorage proceedsAfter;
    private final long timestampMillis;

    private volatile String journalId;
    private volatile boolean newCustomer;

    public TradePlan(Shop shop,
                     TradeOffer offer,
                     UUID customerId,
                     String customerName,
                     InventoryModel inventoryBefore,
                     InventoryModel inventoryAfter,
                     StockStorage stockBefore,
                     StockStorage stockAfter,
                     ProceedsStorage proceedsBefore,
                     ProceedsStorage proceedsAfter) {
        this.shop = Objects.requireNonNull(shop, "shop");
        this.offer = Objects.requireNonNull(offer, "offer");
        this.customerId = Objects.requireNonNull(customerId, "customerId");
        this.customerName = customerName == null ? "" : customerName;
        this.inventoryBefore = Objects.requireNonNull(inventoryBefore, "inventoryBefore");
        this.inventoryAfter = Objects.requireNonNull(inventoryAfter, "inventoryAfter");
        this.stockBefore = Objects.requireNonNull(stockBefore, "stockBefore");
        this.stockAfter = Objects.requireNonNull(stockAfter, "stockAfter");
        this.proceedsBefore = Objects.requireNonNull(proceedsBefore, "proceedsBefore");
        this.proceedsAfter = Objects.requireNonNull(proceedsAfter, "proceedsAfter");
        this.timestampMillis = System.currentTimeMillis();
    }

    public Shop shop() {
        return shop;
    }

    public TradeOffer offer() {
        return offer;
    }

    public UUID customerId() {
        return customerId;
    }

    public String customerName() {
        return customerName;
    }

    public InventoryModel inventoryBefore() {
        return inventoryBefore;
    }

    public InventoryModel inventoryAfter() {
        return inventoryAfter;
    }

    public StockStorage stockBefore() {
        return stockBefore;
    }

    public StockStorage stockAfter() {
        return stockAfter;
    }

    public ProceedsStorage proceedsBefore() {
        return proceedsBefore;
    }

    public ProceedsStorage proceedsAfter() {
        return proceedsAfter;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    public List<Integer> changedInventorySlots() {
        return inventoryAfter.changedSlots(inventoryBefore);
    }

    /** Total items the customer handed over, used for persistent statistics. */
    public int paymentItemCount() {
        int total = 0;
        for (dev.bartershop.core.model.PaymentRequirement payment : offer.payments()) {
            total += payment.amount();
        }
        return total;
    }

    /** Total items the customer received from shop stock. */
    public int deliveredItemCount() {
        return offer.receiveAmount();
    }

    public void applyInventory(InventoryModel current) {
        current.copyFrom(inventoryAfter);
    }

    public void revertInventory(InventoryModel current) {
        current.copyFrom(inventoryBefore);
    }

    public void applyShop() {
        shop.setStock(stockAfter);
        shop.setProceeds(proceedsAfter);
    }

    public void revertShop() {
        shop.setStock(stockBefore);
        shop.setProceeds(proceedsBefore);
    }

    public boolean newCustomer() {
        return newCustomer;
    }

    public void setNewCustomer(boolean newCustomer) {
        this.newCustomer = newCustomer;
    }

    public String journalId() {
        return journalId;
    }

    public void setJournalId(String journalId) {
        this.journalId = journalId;
    }

    public String historyDescription() {
        String actor = customerName.isBlank() ? customerId.toString() : customerName;
        return actor + " | " + offer.describe();
    }

    @Override
    public String toString() {
        return "TradePlan{" + shop.id() + ", " + offer.id() + ", customer=" + customerName + "}";
    }
}

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
import dev.bartershop.core.trade.FailureReason;
import dev.bartershop.core.trade.TradeCommit;
import dev.bartershop.core.trade.TradeEngine;
import dev.bartershop.core.trade.TradeResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MultiPaymentTradeTest {

    private static final ItemData EMERALD = TestData.item("EMERALD");
    private static final ItemData DIAMOND = TestData.item("DIAMOND");
    private static final ItemData GOLD = TestData.item("GOLD_INGOT");
    private static final ItemData IRON = TestData.item("IRON_INGOT");

    private final TradeEngine engine = new TradeEngine(2_000, false);

    @Test
    void allPaymentTypesAreRemovedAndQueued() {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL,
                List.of(new PaymentRequirement(EMERALD, 12, true),
                        new PaymentRequirement(GOLD, 3, true),
                        new PaymentRequirement(IRON, 5, true)),
                DIAMOND, 2, 0L);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 2);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 12);
        customer.add(GOLD, 3);
        customer.add(IRON, 5);
        customer.add(TestData.item("STONE"), 10);

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER,
                "Customer", customer, TradeCommit.NOOP);

        assertTrue(result.success());
        assertEquals(0, customer.countMatching(EMERALD, true));
        assertEquals(0, customer.countMatching(GOLD, true));
        assertEquals(0, customer.countMatching(IRON, true));
        assertEquals(2, customer.countMatching(DIAMOND, true));
        assertEquals(12, shop.proceeds().countOf(EMERALD));
        assertEquals(3, shop.proceeds().countOf(GOLD));
        assertEquals(5, shop.proceeds().countOf(IRON));
        assertEquals(0, shop.stock().count(DIAMOND, true));
    }

    @Test
    void missingOnePaymentTypeLeavesEverythingUntouched() {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL,
                List.of(new PaymentRequirement(EMERALD, 4, true),
                        new PaymentRequirement(GOLD, 4, true)),
                DIAMOND, 1, 0L);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 4);
        customer.add(GOLD, 3);

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER,
                "Customer", customer, TradeCommit.NOOP);

        assertFalse(result.success());
        assertEquals(FailureReason.INSUFFICIENT_PAYMENT, result.failure());
        assertEquals(4, customer.countMatching(EMERALD, true));
        assertEquals(3, customer.countMatching(GOLD, true));
        assertEquals(1, shop.stock().count(DIAMOND, true));
        assertTrue(shop.proceeds().isEmpty());
    }

    @Test
    void buyOfferCanRequireSeveralItemsAndStoresAllOfThem() {
        TradeOffer offer = TradeOffer.create(TradeMode.BUY,
                List.of(new PaymentRequirement(EMERALD, 8, true),
                        new PaymentRequirement(IRON, 2, true)),
                DIAMOND, 1, 0L);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 8);
        customer.add(IRON, 2);

        assertTrue(engine.execute(shop, offer.id(), TestData.CUSTOMER, "C",
                customer, TradeCommit.NOOP).success());
        assertEquals(1, customer.countMatching(DIAMOND, true));
        assertEquals(8, shop.stock().count(EMERALD, true));
        assertEquals(2, shop.stock().count(IRON, true));
        assertEquals(0, shop.stock().count(DIAMOND, true));
        assertTrue(shop.proceeds().isEmpty());
    }

    @Test
    void expiredOfferCannotBeTradedAndKeepsStock() {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL,
                List.of(new PaymentRequirement(EMERALD, 1, true)),
                DIAMOND, 1, System.currentTimeMillis() - 1);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 1);

        TradeResult result = engine.execute(shop, offer.id(), TestData.CUSTOMER,
                "C", customer, TradeCommit.NOOP);

        assertEquals(FailureReason.OFFER_EXPIRED, result.failure());
        assertEquals(1, customer.countMatching(EMERALD, true));
        assertEquals(1, shop.stock().count(DIAMOND, true));
        assertTrue(shop.proceeds().isEmpty());
    }

    @Test
    void maintenanceModeRejectsTradesBeforeAnyMutation() {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL,
                List.of(new PaymentRequirement(EMERALD, 1, true)),
                DIAMOND, 1, 0L);
        StockStorage stock = new StockStorage(27);
        stock.add(DIAMOND, 1);
        Shop shop = TestData.shop(27, stock, new ProceedsStorage(), offer);
        InventoryModel customer = InventoryModel.empty(36);
        customer.add(EMERALD, 1);
        TradeEngine maintenance = new TradeEngine(2_000, false, () -> true);

        TradeResult result = maintenance.execute(shop, offer.id(), TestData.CUSTOMER,
                "C", customer, TradeCommit.NOOP);

        assertEquals(FailureReason.MAINTENANCE, result.failure());
        assertEquals(1, customer.countMatching(EMERALD, true));
        assertEquals(1, shop.stock().count(DIAMOND, true));
    }
}

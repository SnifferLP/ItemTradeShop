package dev.bartershop.core;

import dev.bartershop.TestData;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OfferValidationTest {

    @Test
    void amountsMustBePositiveAndWithinHardCap() {
        assertThrows(IllegalArgumentException.class, () -> TradeOffer.create(
                TradeMode.SELL, TestData.item("DIAMOND"), 0, TestData.item("EMERALD"), 1, true));
        assertThrows(IllegalArgumentException.class, () -> TradeOffer.create(
                TradeMode.SELL, TestData.item("DIAMOND"), 1, TestData.item("EMERALD"), -1, true));
        assertThrows(IllegalArgumentException.class, () -> TradeOffer.create(
                TradeMode.SELL, TestData.item("DIAMOND"), TradeOffer.MAX_AMOUNT + 1,
                TestData.item("EMERALD"), 1, true));
    }

    @Test
    void maximumQuantityIsAccepted() {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL, TestData.item("DIAMOND"),
                TradeOffer.MAX_AMOUNT, TestData.item("EMERALD"), TradeOffer.MAX_AMOUNT, true);
        assertEquals(3456, offer.payAmount());
        assertEquals(3456, offer.receiveAmount());
    }

    @Test
    void editingPreservesIdentity() {
        TradeOffer offer = TradeOffer.create(TradeMode.SELL, TestData.item("DIAMOND"), 1,
                TestData.item("EMERALD"), 2, true);
        TradeOffer edited = offer.withMode(TradeMode.BUY).withPay(TestData.item("STONE"), 3)
                .withReceive(TestData.item("IRON_INGOT"), 4).withExactPay(false);
        assertEquals(offer.id(), edited.id());
        assertEquals(TradeMode.BUY, edited.mode());
        assertEquals(3, edited.payAmount());
        assertEquals(4, edited.receiveAmount());
        assertEquals(false, edited.exactPay());
    }
}

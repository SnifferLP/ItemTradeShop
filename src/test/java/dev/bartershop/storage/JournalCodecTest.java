package dev.bartershop.storage;

import dev.bartershop.TestData;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.StockStack;
import dev.bartershop.storage.JournalCodec.TradePayload;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JournalCodecTest {

    @Test
    void tradePayloadRoundTripsStockAndProceeds() {
        ItemData diamond = TestData.item("DIAMOND", "a", 64);
        ItemData emerald = TestData.item("EMERALD", "b", 64);
        List<StockStack> stock = List.of(new StockStack(diamond, 64), new StockStack(diamond, 12));
        List<StockStack> proceeds = List.of(new StockStack(emerald, 7));

        String encoded = JournalCodec.encodeTrade(stock, proceeds);
        TradePayload decoded = JournalCodec.decodeTrade(encoded);

        assertEquals(2, decoded.stock().size());
        assertEquals(64, decoded.stock().get(0).amount());
        assertEquals(12, decoded.stock().get(1).amount());
        assertEquals(diamond.signature(), decoded.stock().get(0).item().signature());
        assertEquals(1, decoded.proceeds().size());
        assertEquals(emerald.signature(), decoded.proceeds().get(0).item().signature());
        assertEquals(7, decoded.proceeds().get(0).amount());
    }

    @Test
    void itemPayloadRoundTrips() {
        ItemData item = TestData.item("PAPER", "custom", 64);
        String encoded = JournalCodec.encodeItems(List.of(new StockStack(item, 5)));
        List<StockStack> decoded = JournalCodec.decodeItems(encoded);
        assertEquals(1, decoded.size());
        assertEquals(item.signature(), decoded.get(0).item().signature());
        assertEquals(item.serialized(), decoded.get(0).item().serialized());
    }

    @Test
    void emptyPayloadsRoundTrip() {
        TradePayload decoded = JournalCodec.decodeTrade(JournalCodec.encodeTrade(List.of(), List.of()));
        assertEquals(0, decoded.stock().size());
        assertEquals(0, decoded.proceeds().size());
        assertEquals(0, JournalCodec.decodeItems(JournalCodec.encodeItems(List.of())).size());
    }

    @Test
    void malformedPayloadsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decodeTrade("garbage"));
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decodeTrade("V1\nSTOCK\n"));
        assertThrows(IllegalArgumentException.class, () -> JournalCodec.decodeItems("V1\nbroken"));
    }
}

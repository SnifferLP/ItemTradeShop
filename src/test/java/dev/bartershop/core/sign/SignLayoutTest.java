package dev.bartershop.core.sign;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignLayoutTest {

    private static final SignLayout.Text TEXT = text();

    private static SignLayout.Text text() {
        Map<String, String> values = Map.ofEntries(
                Map.entry("sign.status.disabled", "Disabled"),
                Map.entry("sign.status.maintenance", "Maintenance"),
                Map.entry("sign.status.unavailable", "Unavailable"),
                Map.entry("sign.status.no-offers", "No offers"),
                Map.entry("sign.status.expired", "Offers expired"),
                Map.entry("sign.status.out-of-stock", "OUT OF STOCK"),
                Map.entry("sign.line.offers", "<count> offers"),
                Map.entry("sign.line.items", "<count> item types"),
                Map.entry("sign.hint.sell", "SELL · Click"),
                Map.entry("sign.hint.buy", "BUY · Click"),
                Map.entry("sign.hint.browse", "Click to browse"),
                Map.entry("sign.hint.info", "Click for info"));
        return (key, placeholders) -> {
            String value = values.getOrDefault(key, "<missing " + key + ">");
            for (int i = 0; i + 1 < placeholders.length; i += 2) {
                value = value.replace("<" + placeholders[i] + ">", placeholders[i + 1]);
            }
            return value;
        };
    }

    private static SignLayout.OfferView offer(boolean ownerSells, int paymentTypes, int payAmount,
                                              String payName, int receiveAmount, String receiveName,
                                              boolean inStock, boolean expired) {
        return new SignLayout.OfferView(ownerSells, paymentTypes, payAmount, payName,
                receiveAmount, receiveName, inStock, expired);
    }

    private static SignLayout.OfferView sell(String payName, int payAmount,
                                             String receiveName, int receiveAmount,
                                             boolean inStock) {
        return offer(true, 1, payAmount, payName, receiveAmount, receiveName, inStock, false);
    }

    private static String[] build(SignLayout.Status status, SignLayout.OfferView... offers) {
        return SignLayout.build("[shop]", "Omada's Shop", status, List.of(offers), TEXT);
    }

    @Test
    void singleSellOfferShowsGiveGetAndMode() {
        String[] lines = build(SignLayout.Status.OPEN, sell("Oak Log", 16, "Iron", 4, true));

        assertEquals("[shop]", lines[0]);
        assertEquals("16x Oak Log", lines[1]);
        assertEquals("-> 4x Iron", lines[2]);
        assertEquals("SELL · Click", lines[3]);
    }

    @Test
    void singleBuyOfferUsesBuyModeLabel() {
        String[] lines = build(SignLayout.Status.OPEN,
                offer(false, 1, 32, "Wheat", 1, "Emerald", true, false));

        assertEquals("32x Wheat", lines[1]);
        assertEquals("-> 1x Emerald", lines[2]);
        assertEquals("BUY · Click", lines[3]);
    }

    @Test
    void multiplePaymentsAreSummarisedInsteadOfOverflowing() {
        String[] lines = build(SignLayout.Status.OPEN,
                offer(true, 3, 70, "Oak Log", 4, "Iron", true, false));

        assertEquals("3 item types", lines[1]);
        assertEquals("-> 4x Iron", lines[2]);
        assertEquals("SELL · Click", lines[3]);
    }

    @Test
    void outOfStockOfferSaysSoInsteadOfAdvertisingTheTrade() {
        String[] lines = build(SignLayout.Status.OPEN, sell("Oak Log", 16, "Iron", 4, false));

        assertEquals("OUT OF STOCK", lines[3]);
    }

    @Test
    void multipleOffersShowCountAndPrimaryItems() {
        String[] lines = build(SignLayout.Status.OPEN,
                sell("Oak Log", 16, "Iron", 4, true),
                sell("Sand", 8, "Diamond", 1, true),
                sell("Wheat", 32, "Emerald", 1, true));

        assertEquals("3 offers", lines[1]);
        assertEquals("Iron, Diamond", lines[2]);
        assertEquals("Click to browse", lines[3]);
    }

    @Test
    void multipleOffersWithoutAnyStockReportUnavailable() {
        String[] lines = build(SignLayout.Status.OPEN,
                sell("Oak Log", 16, "Iron", 4, false),
                sell("Sand", 8, "Diamond", 1, false));

        assertEquals("OUT OF STOCK", lines[3]);
    }

    @Test
    void expiredOffersAreExcludedAndReported() {
        SignLayout.OfferView expired = offer(true, 1, 16, "Oak Log", 4, "Iron", true, true);
        assertEquals("Offers expired", build(SignLayout.Status.OPEN, expired)[2]);

        String[] mixed = build(SignLayout.Status.OPEN,
                expired,
                sell("Sand", 8, "Diamond", 1, true));
        assertEquals("8x Sand", mixed[1]);
        assertEquals("-> 1x Diamond", mixed[2]);
        assertEquals("SELL · Click", mixed[3]);
    }

    @Test
    void shopWithoutOffersIsExplicit() {
        String[] lines = build(SignLayout.Status.OPEN);

        assertEquals("Omada's Shop", lines[1]);
        assertEquals("No offers", lines[2]);
        assertEquals("Click for info", lines[3]);
    }

    @Test
    void statusesReplaceTheOfferPreview() {
        assertEquals("Disabled", build(SignLayout.Status.DISABLED,
                sell("Oak Log", 16, "Iron", 4, true))[2]);
        assertEquals("Maintenance", build(SignLayout.Status.MAINTENANCE)[2]);
        assertEquals("Unavailable", build(SignLayout.Status.UNAVAILABLE)[2]);
    }

    @Test
    void everyLineFitsMinecraftAndIsNeverBlank() {
        List<String[]> layouts = List.of(
                build(SignLayout.Status.OPEN, sell("Enchanted Golden Apple With A Very Long Name",
                        999, "Netherite Sword Of Doom", 1, true)),
                build(SignLayout.Status.OPEN,
                        sell("Oak Log", 16, "Iron", 4, true),
                        sell("Sand", 8, "Diamond", 1, true)),
                build(SignLayout.Status.DISABLED),
                build(SignLayout.Status.MAINTENANCE),
                build(SignLayout.Status.UNAVAILABLE),
                build(SignLayout.Status.OPEN));

        for (String[] lines : layouts) {
            assertEquals(4, lines.length);
            for (String line : lines) {
                assertFalse(line.isBlank(), "sign line must not be blank");
                assertTrue(line.length() <= SignLayout.LINE_LENGTH,
                        "sign line too long: '" + line + "' (" + line.length() + ")");
            }
        }
    }

    @Test
    void longItemNamesAreClippedWithVisibleEllipsis() {
        String[] lines = build(SignLayout.Status.OPEN,
                sell("Enchanted Golden Apple", 64, "Netherite Sword", 1, true));

        assertEquals("64x Enchanted …", lines[1]);
        assertEquals("-> 1x Netherit…", lines[2]);
    }

    @Test
    void markerIsPreservedExactlyForDetection() {
        String[] lines = SignLayout.build("[OmadaShop]", "Iron & Diamonds",
                SignLayout.Status.OPEN, List.of(sell("Iron", 3, "Diamond", 1, true)), TEXT);

        assertEquals("[OmadaShop]", lines[0]);
        assertEquals("3x Iron", lines[1]);
    }
}

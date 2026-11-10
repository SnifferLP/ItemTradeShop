package dev.bartershop.core.sign;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the four lines of a physical shop sign.
 *
 * <p>Minecraft renders at most 15 characters per sign line, so every content
 * line is clipped here rather than left to overflow. The layout is deliberately
 * customer-centric: the first item line is what the customer hands over, the
 * second is what the customer receives, and the last line names the offer mode
 * or the reason the shop cannot be used right now.</p>
 *
 * <p>Pure and Bukkit-free so that layouts for every shop state can be tested
 * without a server. Localisation is injected through {@link Text}; the service
 * binds it to the plugin's message files.</p>
 */
public final class SignLayout {

    /** Minecraft Java Edition renders 15 characters per sign line. */
    public static final int LINE_LENGTH = 15;

    private static final String ELLIPSIS = "…";

    /** Shop states that replace the offer preview with a status message. */
    public enum Status {
        OPEN,
        DISABLED,
        MAINTENANCE,
        UNAVAILABLE
    }

    /**
     * One offer as it appears to a customer.
     *
     * @param ownerSells     true for {@code TradeMode.SELL}, false for {@code BUY}
     * @param paymentTypes   number of different payment item types
     * @param payAmount      total amount of items the customer hands over
     * @param payName        display name of the single payment item, if there is one
     * @param receiveAmount  amount the customer receives
     * @param receiveName    display name of the item the customer receives
     * @param inStock        whether stock can satisfy this offer right now
     * @param expired        whether the offer is past its expiry
     */
    public record OfferView(
            boolean ownerSells,
            int paymentTypes,
            int payAmount,
            String payName,
            int receiveAmount,
            String receiveName,
            boolean inStock,
            boolean expired) {

        public OfferView {
            payName = payName == null ? "" : payName;
            receiveName = receiveName == null ? "" : receiveName;
        }
    }

    /** Resolves a message key with optional {@code <name>/value} placeholder pairs. */
    @FunctionalInterface
    public interface Text {
        String text(String key, String... placeholders);
    }

    private SignLayout() {
    }

    public static String[] build(String marker, String shopName, Status status,
                                 List<OfferView> offers, Text text) {
        String[] lines = new String[4];
        lines[0] = marker == null ? "" : marker;
        List<OfferView> all = offers == null ? List.of() : List.copyOf(offers);

        if (status != Status.OPEN) {
            return statusLayout(lines, shopName, text.text(statusKey(status)), text);
        }
        if (all.isEmpty()) {
            return statusLayout(lines, shopName, text.text("sign.status.no-offers"), text);
        }

        List<OfferView> active = new ArrayList<>();
        for (OfferView offer : all) {
            if (!offer.expired()) {
                active.add(offer);
            }
        }
        if (active.isEmpty()) {
            return statusLayout(lines, shopName, text.text("sign.status.expired"), text);
        }
        if (active.size() == 1) {
            OfferView offer = active.get(0);
            lines[1] = clip(offer.paymentTypes() > 1
                    ? text.text("sign.line.items", "count", String.valueOf(offer.paymentTypes()))
                    : amountName(offer.payAmount(), offer.payName()));
            lines[2] = clip("-> " + amountName(offer.receiveAmount(), offer.receiveName()));
            lines[3] = clip(offer.inStock()
                    ? text.text(offer.ownerSells() ? "sign.hint.sell" : "sign.hint.buy")
                    : text.text("sign.status.out-of-stock"));
            return lines;
        }

        lines[1] = clip(text.text("sign.line.offers", "count", String.valueOf(active.size())));
        lines[2] = clip(primaryItems(active));
        boolean anyAvailable = active.stream().anyMatch(OfferView::inStock);
        lines[3] = clip(anyAvailable
                ? text.text("sign.hint.browse")
                : text.text("sign.status.out-of-stock"));
        return lines;
    }

    private static String[] statusLayout(String[] lines, String shopName, String statusLine, Text text) {
        lines[1] = clip(shopName);
        lines[2] = clip(statusLine);
        lines[3] = clip(text.text("sign.hint.info"));
        return lines;
    }

    private static String statusKey(Status status) {
        return switch (status) {
            case DISABLED -> "sign.status.disabled";
            case MAINTENANCE -> "sign.status.maintenance";
            case UNAVAILABLE -> "sign.status.unavailable";
            case OPEN -> "sign.status.no-offers";
        };
    }

    /** Names of the first two distinct receive items, e.g. {@code Iron, Diamond}. */
    private static String primaryItems(List<OfferView> offers) {
        Set<String> names = new LinkedHashSet<>();
        for (OfferView offer : offers) {
            if (!offer.receiveName().isBlank()) {
                names.add(offer.receiveName());
            }
            if (names.size() == 2) {
                break;
            }
        }
        return String.join(", ", names);
    }

    private static String amountName(int amount, String name) {
        return (amount > 0 ? amount + "x " : "") + name;
    }

    /**
     * Clips to the sign line budget, appending an ellipsis so a shortened name
     * is visibly shortened instead of silently cut.
     */
    static String clip(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= LINE_LENGTH) {
            return trimmed;
        }
        return trimmed.substring(0, LINE_LENGTH - 1) + ELLIPSIS;
    }
}

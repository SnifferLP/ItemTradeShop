package dev.bartershop.core.model;

/**
 * Direction of a single offer from the shop owner's perspective.
 *
 * <p>A shop supports two-way trading by holding independent SELL and BUY
 * offers at the same time. Both modes exchange physical items only.</p>
 */
public enum TradeMode {
    /** The owner sells an item: customer pays, receives from shop stock, payment becomes owner proceeds. */
    SELL("Selling", "Owner sells stock for items"),
    /** The owner buys an item: customer supplies it into stock, customer receives an item the owner stocked as payment. */
    BUY("Buying", "Owner buys items and pays with stocked items");

    private final String display;
    private final String description;

    TradeMode(String display, String description) {
        this.display = display;
        this.description = description;
    }

    public String display() {
        return display;
    }

    public String description() {
        return description;
    }
}

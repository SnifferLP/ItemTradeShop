package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Lists offers with live availability, expiry and payment requirements. */
public final class OfferListMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final Shop shop;
    private final int page;

    public OfferListMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent) {
        this(plugin, player, shop, parent, 0);
    }

    public OfferListMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent, int page) {
        super(plugin, player, parent);
        this.shop = shop;
        this.page = Math.max(0, page);
    }

    @Override
    protected String titleKey() {
        return "gui.offers.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        List<TradeOffer> offers = shop.offers();
        int pages = Math.max(1, (offers.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, offers.size());
        Set<UUID> warned = plugin.shopManager().lowStockOfferIds(shop);
        for (int i = start; i < end; i++) {
            TradeOffer offer = offers.get(i);
            int slot = 9 + (i - start);
            bind(slot, offerIcon(offer, warned.contains(offer.id())),
                    () -> new OfferEditorMenu(plugin, player, shop, offer, this).open());
        }
        if (offers.isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "gui.offers.empty",
                    List.of("gui.offers.empty-lore")));
        }

        set(49, Icons.button(plugin, Material.LIME_DYE, "gui.offers.add",
                List.of("gui.offers.add-lore")));
        bind(49, getInventory().getItem(49), () -> {
            if (shop.offers().size() >= plugin.settings().maxOffersPerShop()) {
                plugin.messages().send(player, "offer.limit",
                        "limit", String.valueOf(plugin.settings().maxOffersPerShop()));
                Feedback.error(player);
                return;
            }
            new OfferEditorMenu(plugin, player, shop, null, this).open();
        });

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);

        if (current > 0) {
            set(46, Icons.previous(plugin, current));
            bind(46, getInventory().getItem(46),
                    () -> new OfferListMenu(plugin, player, shop, parent, current - 1).open());
        }
        if (current + 1 < pages) {
            set(52, Icons.next(plugin, current + 2));
            bind(52, getInventory().getItem(52),
                    () -> new OfferListMenu(plugin, player, shop, parent, current + 1).open());
        }
    }

    private ItemStack offerIcon(TradeOffer offer, boolean warned) {
        long now = System.currentTimeMillis();
        boolean expired = offer.isExpired(now);
        List<String> lore = new ArrayList<>();
        lore.add(plugin.messages().raw("gui.offers.mode",
                "mode", Messages.rich(plugin.messages().raw(modeKey(offer.mode())))));
        lore.add(plugin.messages().raw("gui.offers.matching",
                "mode", Messages.rich(plugin.messages().raw(allExact(offer)
                        ? "gui.offer-editor.exact" : "gui.offer-editor.any"))));
        for (PaymentRequirement payment : offer.payments()) {
            lore.add(plugin.messages().raw("gui.offers.pay",
                    "amount", String.valueOf(payment.amount()),
                    "item", GuiUtil.safe(payment.item().prettyName())));
        }
        lore.add(plugin.messages().raw("gui.offers.receive",
                "amount", String.valueOf(offer.receiveAmount()),
                "item", GuiUtil.safe(offer.receiveName())));
        lore.add(plugin.messages().raw("gui.offers.stock",
                "stock", String.valueOf(stockFor(offer)),
                "needed", String.valueOf(neededFor(offer))));
        if (offer.hasExpiry()) {
            lore.add(plugin.messages().raw(expired ? "gui.offers.expired" : "gui.offers.expires",
                    "time", GuiUtil.time(offer.expiresAt())));
        }
        if (warned) {
            lore.add(plugin.messages().raw("gui.offers.low-stock"));
        }
        lore.add(plugin.messages().raw("gui.offers.click-edit"));
        String name = plugin.messages().raw(modeKey(offer.mode())) + ": "
                + offer.paymentSummary() + " → " + offer.receiveAmount() + "x " + offer.receiveName();
        return Icons.item(offer.receiveItem(), 1, GuiUtil.safe(name), lore);
    }

    private static boolean allExact(TradeOffer offer) {
        return offer.payments().stream().allMatch(PaymentRequirement::exact);
    }

    private int stockFor(TradeOffer offer) {
        return shop.stock().count(offer.receiveItem(), true);
    }

    private int neededFor(TradeOffer offer) {
        return offer.receiveAmount();
    }

    static String modeKey(TradeMode mode) {
        return mode == TradeMode.SELL ? "gui.offer-editor.mode-sell" : "gui.offer-editor.mode-buy";
    }
}

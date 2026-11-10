package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Customer offer browser. Trades always execute from the GUI, never from chest clicks. */
public final class CustomerShopMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final Shop shop;
    private final int page;

    public CustomerShopMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent) {
        this(plugin, player, shop, parent, 0);
    }

    public CustomerShopMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent, int page) {
        super(plugin, player, parent);
        this.shop = shop;
        this.page = Math.max(0, page);
    }

    @Override
    protected String titleKey() {
        return "gui.customer.title";
    }

    @Override
    protected Component title() {
        return Component.text(shop.name());
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        long now = System.currentTimeMillis();
        List<TradeOffer> offers = shop.offers();
        long activeOffers = offers.stream().filter(offer -> !offer.isExpired(now)).count();
        List<String> info = new ArrayList<>();
        info.add(plugin.messages().raw("gui.customer.owner", "owner", GuiUtil.safe(shop.ownerName())));
        info.add(plugin.messages().raw("gui.customer.trades", "count", String.valueOf(activeOffers)));
        info.add(plugin.messages().raw(shop.enabled() ? "gui.shops.enabled" : "gui.shops.disabled"));
        if (plugin.maintenanceMode()) {
            info.add(plugin.messages().raw("gui.customer.maintenance"));
        }
        if (shop.orphaned()) {
            info.add(plugin.messages().raw("gui.customer.orphaned"));
        }
        info.add(plugin.messages().raw("gui.customer.info-lore"));
        set(4, Icons.of(Material.CHEST, GuiUtil.safe(shop.name()), info));

        boolean unavailable = !shop.enabled() || shop.orphaned() || plugin.maintenanceMode();
        if (unavailable) {
            set(8, Icons.button(plugin, Material.BARRIER, "gui.customer.unavailable", List.of()));
        }

        int pages = Math.max(1, (offers.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, offers.size());
        for (int i = start; i < end; i++) {
            TradeOffer offer = offers.get(i);
            boolean expired = offer.isExpired(now);
            boolean available = !unavailable && !expired && availableStock(offer);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.customer.mode",
                    "mode", Messages.rich(plugin.messages().raw(OfferListMenu.modeKey(offer.mode())))));
            lore.add(plugin.messages().raw("gui.customer.matching",
                    "mode", Messages.rich(plugin.messages().raw(offer.payments().stream()
                            .allMatch(PaymentRequirement::exact)
                            ? "gui.offer-editor.exact" : "gui.offer-editor.any"))));
            for (PaymentRequirement payment : offer.payments()) {
                lore.add(plugin.messages().raw("gui.customer.pay",
                        "amount", String.valueOf(payment.amount()),
                        "item", GuiUtil.safe(payment.item().prettyName())));
            }
            lore.add(plugin.messages().raw("gui.customer.receive",
                    "amount", String.valueOf(offer.receiveAmount()),
                    "item", GuiUtil.safe(offer.receiveName())));
            lore.add(plugin.messages().raw("gui.customer.available",
                    "stock", String.valueOf(availableCount(offer))));
            if (offer.hasExpiry()) {
                lore.add(plugin.messages().raw(expired ? "gui.customer.expired" : "gui.customer.expires",
                        "time", GuiUtil.time(offer.expiresAt())));
            }
            lore.add(plugin.messages().raw(expired ? "gui.customer.expired-action"
                    : available ? "gui.customer.click" : "gui.customer.out-of-stock"));
            ItemStack icon = Icons.item(offer.receiveItem(), 1,
                    GuiUtil.safe(offer.receiveName() + " x" + offer.receiveAmount()), lore);
            bind(9 + (i - start), icon, () -> {
                if (!shop.enabled()) {
                    plugin.messages().send(player, "trade.failed.disabled");
                    Feedback.error(player);
                    return;
                }
                if (shop.orphaned()) {
                    plugin.messages().send(player, "trade.failed.orphaned");
                    Feedback.error(player);
                    return;
                }
                if (plugin.maintenanceMode()) {
                    plugin.messages().send(player, "trade.failed.maintenance");
                    Feedback.error(player);
                    return;
                }
                if (expired) {
                    plugin.messages().send(player, "trade.failed.expired");
                    Feedback.error(player);
                    return;
                }
                if (!availableStock(offer)) {
                    plugin.messages().send(player, "trade.failed.out-of-stock");
                    Feedback.error(player);
                    return;
                }
                new TradeConfirmMenu(plugin, player, shop, offer, this).open();
            });
        }
        if (offers.isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "gui.customer.empty",
                    List.of("gui.customer.empty-lore")));
        }

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
        if (current > 0) {
            set(48, Icons.previous(plugin, current));
            bind(48, getInventory().getItem(48),
                    () -> new CustomerShopMenu(plugin, player, shop, parent, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, Icons.next(plugin, current + 2));
            bind(50, getInventory().getItem(50),
                    () -> new CustomerShopMenu(plugin, player, shop, parent, current + 1).open());
        }
    }

    private boolean availableStock(TradeOffer offer) {
        return shop.stock().count(offer.receiveItem(), true) >= offer.receiveAmount();
    }

    private int availableCount(TradeOffer offer) {
        return shop.stock().count(offer.receiveItem(), true);
    }
}

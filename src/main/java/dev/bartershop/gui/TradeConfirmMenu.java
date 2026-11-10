package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Final trade confirmation. Re-validates everything inside the trade engine. */
public final class TradeConfirmMenu extends Menu {

    private final Shop shop;
    private final TradeOffer offer;

    public TradeConfirmMenu(BarterShopPlugin plugin, Player player, Shop shop,
                            TradeOffer offer, Menu parent) {
        super(plugin, player, parent);
        this.shop = shop;
        this.offer = offer;
    }

    @Override
    protected String titleKey() {
        return "gui.trade-confirm.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        long now = System.currentTimeMillis();
        boolean expired = offer.isExpired(now);
        boolean owner = shop.owner().equals(player.getUniqueId());
        boolean available = shop.enabled() && !shop.orphaned()
                && !plugin.maintenanceMode() && !expired && stockAvailable();

        set(4, Icons.of(Material.PAPER, plugin.messages().raw("gui.trade-confirm.info"),
                List.of(plugin.messages().raw("gui.trade-confirm.info-lore"),
                        plugin.messages().raw(offer.mode() == TradeMode.SELL
                                ? "gui.trade-confirm.info-sell" : "gui.trade-confirm.info-buy"))));

        List<PaymentRequirement> payments = offer.payments();
        for (int i = 0; i < payments.size(); i++) {
            PaymentRequirement payment = payments.get(i);
            set(9 + i, Icons.item(payment.item(), 1,
                    GuiUtil.safe(payment.item().prettyName() + " x" + payment.amount()),
                    List.of(plugin.messages().raw("gui.trade-confirm.pay-lore",
                            "amount", String.valueOf(payment.amount())))));
        }
        set(22, Icons.of(Material.ARROW, plugin.messages().raw("gui.trade-confirm.arrow"),
                List.of(plugin.messages().raw("gui.trade-confirm.arrow-lore"))));
        set(31, Icons.item(offer.receiveItem(), 1,
                GuiUtil.safe(offer.receiveName() + " x" + offer.receiveAmount()),
                List.of(plugin.messages().raw("gui.trade-confirm.receive-lore",
                        "amount", String.valueOf(offer.receiveAmount())))));

        if (offer.hasExpiry()) {
            set(33, Icons.of(expired ? Material.GRAY_DYE : Material.CLOCK,
                    plugin.messages().raw(expired ? "gui.trade-confirm.expired"
                            : "gui.trade-confirm.expires", "time", GuiUtil.time(offer.expiresAt())),
                    List.of()));
        }

        List<String> confirmLore = new ArrayList<>();
        confirmLore.add(plugin.messages().raw(available
                ? "gui.trade-confirm.confirm-lore" : "gui.trade-confirm.unavailable"));
        if (owner) {
            confirmLore.add(plugin.messages().raw("gui.trade-confirm.owner"));
        }
        set(49, Icons.of(available && !owner ? Material.LIME_DYE : Material.GRAY_DYE,
                plugin.messages().raw("gui.trade-confirm.confirm"), confirmLore));
        bind(49, getInventory().getItem(49), () -> {
            if (owner) {
                plugin.messages().send(player, "trade.failed.owner");
                Feedback.error(player);
                return;
            }
            var result = plugin.tradeService().trade(player, shop, offer.id());
            if (result.success()) {
                Feedback.success(player);
                player.closeInventory();
            } else {
                Feedback.error(player);
                refresh();
            }
        });

        set(45, Icons.button(plugin, Material.RED_DYE, "gui.trade-confirm.cancel", List.of()));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }

    private boolean stockAvailable() {
        return shop.stock().count(offer.receiveItem(), true) >= offer.receiveAmount();
    }
}

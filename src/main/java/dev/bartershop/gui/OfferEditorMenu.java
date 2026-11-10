package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.TradeMode;
import dev.bartershop.core.model.TradeOffer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Edits one offer entirely through inventories; no chat or special items. */
public final class OfferEditorMenu extends Menu {

    private final Shop shop;
    private final UUID originalId;
    private TradeMode mode;
    private List<PaymentRequirement> payments = new ArrayList<>();
    private ItemData receiveItem;
    private int receiveAmount;
    private long expiryHours;

    public OfferEditorMenu(BarterShopPlugin plugin, Player player, Shop shop, TradeOffer original, Menu parent) {
        super(plugin, player, parent);
        this.shop = shop;
        this.originalId = original == null ? null : original.id();
        if (original == null) {
            this.mode = TradeMode.SELL;
            this.receiveAmount = 1;
        } else {
            this.mode = original.mode();
            this.payments = new ArrayList<>(original.payments());
            this.receiveItem = original.receiveItem();
            this.receiveAmount = original.receiveAmount();
            if (original.hasExpiry()) {
                long remaining = original.remainingMillis(System.currentTimeMillis());
                this.expiryHours = Math.max(1L, (remaining + 3_599_999L) / 3_600_000L);
            }
        }
    }

    @Override
    protected String titleKey() {
        return originalId == null ? "gui.offer-editor.title-new" : "gui.offer-editor.title-edit";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        set(4, Icons.of(Material.BOOK, plugin.messages().raw("gui.offer-editor.info"),
                List.of(plugin.messages().raw("gui.offer-editor.info1"),
                        plugin.messages().raw("gui.offer-editor.info2"),
                        plugin.messages().raw("gui.offer-editor.info3"))));

        set(20, Icons.of(Material.HOPPER, plugin.messages().raw("gui.offer-editor.mode",
                        "mode", Messages.rich(plugin.messages().raw(mode == TradeMode.SELL
                                ? "gui.offer-editor.mode-sell" : "gui.offer-editor.mode-buy"))),
                List.of(plugin.messages().raw("gui.offer-editor.mode-lore"),
                        plugin.messages().raw("gui.offer-editor.mode-lore2"),
                        plugin.messages().raw("gui.offer-editor.mode-click"))));
        bind(20, getInventory().getItem(20), () -> {
            mode = mode == TradeMode.SELL ? TradeMode.BUY : TradeMode.SELL;
            Feedback.confirm(player);
            refresh();
        });

        set(22, paymentSummary());
        bind(22, getInventory().getItem(22), () ->
                new PaymentListMenu(plugin, player, this, payments, updated -> {
                    payments = new ArrayList<>(updated);
                }).open());

        set(24, receiveItem == null
                ? Icons.of(Material.GRAY_DYE, plugin.messages().raw("gui.offer-editor.receive-item-none"),
                        List.of(plugin.messages().raw("gui.offer-editor.item-click")))
                : Icons.item(receiveItem, 1, GuiUtil.safe(receiveItem.prettyName()),
                        List.of(plugin.messages().raw("gui.offer-editor.item-click"))));
        bind(24, getInventory().getItem(24), () ->
                new ItemPickerMenu(plugin, player, this, (data, suggested) -> {
                    receiveItem = data;
                    receiveAmount = Math.max(1, Math.min(suggested, plugin.settings().maxOfferAmount()));
                }).open());

        set(30, Icons.of(Material.GOLD_NUGGET, plugin.messages().raw("gui.offer-editor.receive-amount",
                        "amount", String.valueOf(receiveAmount)),
                List.of(plugin.messages().raw("gui.offer-editor.amount-click"))));
        bind(30, getInventory().getItem(30), () ->
                new AmountMenu(plugin, player, this, receiveAmount,
                        plugin.settings().maxOfferAmount(), amount -> receiveAmount = amount).open());

        boolean allExact = !payments.isEmpty()
                && payments.stream().allMatch(PaymentRequirement::exact);
        set(32, Icons.of(allExact ? Material.NAME_TAG : Material.PAPER,
                plugin.messages().raw("gui.offer-editor.matching",
                        "mode", Messages.rich(plugin.messages().raw(allExact
                                ? "gui.offer-editor.exact" : "gui.offer-editor.any"))),
                List.of(plugin.messages().raw("gui.offer-editor.matching-lore"),
                        plugin.messages().raw("gui.offer-editor.matching-lore2"),
                        plugin.messages().raw("gui.offer-editor.matching-lore3"))));
        bind(32, getInventory().getItem(32), () -> {
            if (payments.isEmpty()) {
                plugin.messages().send(player, "offer.incomplete");
                Feedback.error(player);
                return;
            }
            List<PaymentRequirement> updated = payments.stream()
                    .map(payment -> new PaymentRequirement(
                            payment.item(), payment.amount(), !allExact))
                    .toList();
            payments = new ArrayList<>(updated);
            Feedback.confirm(player);
            refresh();
        });

        set(34, Icons.of(Material.CLOCK,
                plugin.messages().raw("gui.offer-editor.expiry",
                        "duration", Messages.rich(expiryLabel())),
                List.of(plugin.messages().raw("gui.offer-editor.expiry-lore"),
                        plugin.messages().raw("gui.offer-editor.expiry-click"))));
        bind(34, getInventory().getItem(34), () -> {
            expiryHours = nextExpiry(expiryHours);
            Feedback.confirm(player);
            refresh();
        });

        boolean ready = !payments.isEmpty() && receiveItem != null && receiveAmount >= 1;
        set(49, Icons.of(ready ? Material.LIME_DYE : Material.GRAY_DYE,
                plugin.messages().raw("gui.offer-editor.save"),
                List.of(plugin.messages().raw(ready ? "gui.offer-editor.save-lore"
                        : "gui.offer-editor.save-incomplete"))));
        bind(49, getInventory().getItem(49), this::save);

        if (originalId != null) {
            set(47, Icons.button(plugin, Material.LAVA_BUCKET, "gui.offer-editor.delete",
                    List.of("gui.offer-editor.delete-lore")));
            bind(47, getInventory().getItem(47), () ->
                    new ConfirmMenu(plugin, player, this,
                            plugin.messages().raw("gui.offer-editor.delete-confirm"),
                            () -> {
                                plugin.shopManager().removeOffer(player, shop, originalId);
                                new OfferListMenu(plugin, player, shop, returnTo()).open();
                            }).open());
        }

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }

    private ItemStack paymentSummary() {
        List<String> lore = new ArrayList<>();
        if (payments.isEmpty()) {
            lore.add(plugin.messages().raw("gui.offer-editor.payments-none"));
        } else {
            for (PaymentRequirement payment : payments) {
                lore.add(plugin.messages().raw("gui.offer-editor.payment-line",
                        "amount", String.valueOf(payment.amount()),
                        "item", GuiUtil.safe(payment.item().prettyName())));
            }
        }
        lore.add(plugin.messages().raw("gui.offer-editor.payments-edit"));
        Material material = payments.isEmpty() ? Material.PAPER : Material.WRITABLE_BOOK;
        return Icons.of(material, plugin.messages().raw("gui.offer-editor.payments",
                "count", String.valueOf(payments.size())), lore);
    }

    private String expiryLabel() {
        if (expiryHours <= 0) {
            return plugin.messages().raw("gui.offer-editor.expiry-never");
        }
        return plugin.messages().raw("gui.offer-editor.expiry-hours",
                "hours", String.valueOf(expiryHours));
    }

    private long nextExpiry(long current) {
        List<Long> options = plugin.settings().offerExpiryHours();
        if (options.isEmpty()) {
            return 0L;
        }
        int index = options.indexOf(current);
        if (index < 0) {
            return options.get(0);
        }
        return options.get((index + 1) % options.size());
    }

    private void save() {
        if (payments.isEmpty() || receiveItem == null) {
            plugin.messages().send(player, "offer.incomplete");
            Feedback.error(player);
            return;
        }
        if (receiveAmount < 1 || receiveAmount > plugin.settings().maxOfferAmount()) {
            plugin.messages().send(player, "offer.amount-limit",
                    "limit", String.valueOf(plugin.settings().maxOfferAmount()));
            Feedback.error(player);
            return;
        }
        for (PaymentRequirement payment : payments) {
            if (payment.amount() < 1 || payment.amount() > plugin.settings().maxOfferAmount()) {
                plugin.messages().send(player, "offer.amount-limit",
                        "limit", String.valueOf(plugin.settings().maxOfferAmount()));
                Feedback.error(player);
                return;
            }
        }
        long expiresAt = expiryHours <= 0 ? 0
                : System.currentTimeMillis() + Math.min(expiryHours, 24L * 365L * 10L) * 3_600_000L;
        boolean allExact = payments.stream().allMatch(PaymentRequirement::exact);
        TradeOffer offer = new TradeOffer(
                originalId == null ? UUID.randomUUID() : originalId,
                mode, payments, receiveItem, receiveAmount, allExact, expiresAt);
        boolean success = originalId == null
                ? plugin.shopManager().addOffer(player, shop, offer)
                : plugin.shopManager().updateOffer(player, shop, offer);
        if (success) {
            plugin.messages().send(player, "offer.saved");
            Feedback.success(player);
            new OfferListMenu(plugin, player, shop, returnTo()).open();
        } else {
            Feedback.error(player);
        }
    }

    /** The editor's parent is the offer list; skip it when rebuilding after a save. */
    private Menu returnTo() {
        return parent instanceof OfferListMenu ? parent.parentMenu() : parent;
    }
}

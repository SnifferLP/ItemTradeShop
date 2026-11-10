package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.core.model.PaymentRequirement;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Edits one item type required by a multi-payment offer. */
public final class PaymentDetailMenu extends Menu {

    private final List<PaymentRequirement> payments;
    private final int index;
    private ItemData item;
    private int amount;
    private boolean exact;

    public PaymentDetailMenu(BarterShopPlugin plugin, Player player, Menu parent,
                             List<PaymentRequirement> payments, int index) {
        super(plugin, player, parent);
        this.payments = payments;
        this.index = index;
        PaymentRequirement payment = payments.get(index);
        this.item = payment.item();
        this.amount = payment.amount();
        this.exact = payment.exact();
    }

    @Override
    protected String titleKey() {
        return "gui.payment.title";
    }

    @Override
    protected int size() {
        return 45;
    }

    @Override
    protected void render() {
        clear();
        set(4, Icons.of(Material.PAPER, plugin.messages().raw("gui.payment.info"),
                List.of(plugin.messages().raw("gui.payment.info-lore"))));

        List<String> itemLore = List.of(plugin.messages().raw("gui.payment.item-lore"));
        set(20, Icons.item(item, 1, GuiUtil.safe(item.prettyName()), itemLore));
        bind(20, getInventory().getItem(20), () ->
                new ItemPickerMenu(plugin, player, this, (data, suggested) -> {
                    item = data;
                    amount = Math.max(1, Math.min(suggested, plugin.settings().maxOfferAmount()));
                }).open());

        set(22, Icons.of(Material.GOLD_NUGGET,
                plugin.messages().raw("gui.payment.amount", "amount", String.valueOf(amount)),
                List.of(plugin.messages().raw("gui.payment.amount-lore"))));
        bind(22, getInventory().getItem(22), () ->
                new AmountMenu(plugin, player, this, amount, plugin.settings().maxOfferAmount(),
                        value -> amount = value).open());

        set(24, Icons.of(exact ? Material.NAME_TAG : Material.PAPER,
                plugin.messages().raw("gui.payment.matching",
                        "mode", Messages.rich(plugin.messages().raw(exact
                                ? "gui.offer-editor.exact" : "gui.offer-editor.any"))),
                List.of(plugin.messages().raw("gui.payment.matching-lore"))));
        bind(24, getInventory().getItem(24), () -> {
            exact = !exact;
            refresh();
        });

        set(40, Icons.button(plugin, Material.LAVA_BUCKET, "gui.payment.remove",
                List.of("gui.payment.remove-lore")));
        bind(40, getInventory().getItem(40), () ->
                new ConfirmMenu(plugin, player, this,
                        plugin.messages().raw("gui.payment.remove-confirm"), () -> {
                            payments.remove(index);
                            parentMenu().open();
                        }).open());

        set(36, Icons.button(plugin, Material.RED_DYE, "gui.cancel", List.of()));
        bind(36, getInventory().getItem(36), this::back);
        set(44, Icons.button(plugin, Material.LIME_DYE, "gui.confirm.action",
                List.of("gui.payment.save-lore")));
        bind(44, getInventory().getItem(44), () -> {
            payments.set(index, new PaymentRequirement(item, amount, exact));
            Feedback.confirm(player);
            parentMenu().open();
        });
    }
}

package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import dev.bartershop.core.model.PaymentRequirement;
import dev.bartershop.core.model.TradeOffer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Edits the complete list of item types a customer must hand over. */
public final class PaymentListMenu extends Menu {

    private final List<PaymentRequirement> payments;
    private final Consumer<List<PaymentRequirement>> onSave;

    public PaymentListMenu(BarterShopPlugin plugin, Player player, Menu parent,
                           List<PaymentRequirement> payments,
                           Consumer<List<PaymentRequirement>> onSave) {
        super(plugin, player, parent);
        this.payments = new ArrayList<>(payments);
        this.onSave = onSave;
    }

    @Override
    protected String titleKey() {
        return "gui.payments.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        set(4, Icons.of(Material.PAPER, plugin.messages().raw("gui.payments.info"),
                List.of(plugin.messages().raw("gui.payments.info-lore",
                                "count", String.valueOf(payments.size()),
                                "max", String.valueOf(TradeOffer.MAX_PAYMENT_TYPES)))));

        for (int i = 0; i < payments.size() && i < 18; i++) {
            PaymentRequirement payment = payments.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.payments.amount",
                    "amount", String.valueOf(payment.amount())));
            lore.add(plugin.messages().raw("gui.payments.matching",
                    "mode", Messages.rich(plugin.messages().raw(payment.exact()
                            ? "gui.offer-editor.exact" : "gui.offer-editor.any"))));
            lore.add(plugin.messages().raw("gui.payments.edit"));
            int index = i;
            ItemStack icon = Icons.item(payment.item(), 1,
                    GuiUtil.safe(payment.item().prettyName() + " x" + payment.amount()), lore);
            bind(9 + i, icon, () -> new PaymentDetailMenu(
                    plugin, player, this, payments, index).open());
        }
        if (payments.isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "gui.payments.empty",
                    List.of("gui.payments.empty-lore")));
        }

        set(48, Icons.button(plugin, Material.LIME_DYE, "gui.payments.add",
                List.of("gui.payments.add-lore")));
        bind(48, getInventory().getItem(48), () -> {
            if (payments.size() >= TradeOffer.MAX_PAYMENT_TYPES) {
                plugin.messages().send(player, "offer.payment-limit",
                        "limit", String.valueOf(TradeOffer.MAX_PAYMENT_TYPES));
                Feedback.error(player);
                return;
            }
            new ItemPickerMenu(plugin, player, this, (data, suggested) -> {
                int amount = Math.max(1, Math.min(suggested, plugin.settings().maxOfferAmount()));
                payments.add(new PaymentRequirement(data, amount, true));
                refresh();
            }).open();
        });

        set(49, Icons.button(plugin, Material.EMERALD, "gui.payments.save",
                List.of("gui.payments.save-lore")));
        bind(49, getInventory().getItem(49), () -> {
            if (payments.isEmpty()) {
                plugin.messages().send(player, "offer.incomplete");
                Feedback.error(player);
                return;
            }
            onSave.accept(List.copyOf(payments));
            Feedback.confirm(player);
            parentMenu().open();
        });

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }
}

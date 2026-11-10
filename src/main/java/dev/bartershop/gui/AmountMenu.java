package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.function.IntConsumer;

/** Exact quantity picker used by offer configuration. */
public final class AmountMenu extends Menu {

    private final int max;
    private final IntConsumer onConfirm;
    private int amount;

    public AmountMenu(BarterShopPlugin plugin, Player player, Menu parent,
                      int initial, int max, IntConsumer onConfirm) {
        super(plugin, player, parent);
        this.max = Math.max(1, max);
        this.amount = Math.max(1, Math.min(initial, this.max));
        this.onConfirm = onConfirm;
    }

    @Override
    protected String titleKey() {
        return "gui.amount.title";
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void render() {
        clear();

        set(4, Icons.of(Material.PAPER, plugin.messages().raw("gui.amount.current",
                        "amount", String.valueOf(amount)),
                List.of(plugin.messages().raw("gui.amount.current-lore",
                        "stacks", String.format("%.2f", amount / 64.0)))));

        bindStep(10, Material.RED_DYE, -64, "gui.amount.minus64");
        bindStep(11, Material.RED_STAINED_GLASS_PANE, -16, "gui.amount.minus16");
        bindStep(12, Material.RED_STAINED_GLASS_PANE, -1, "gui.amount.minus1");
        bindStep(14, Material.LIME_STAINED_GLASS_PANE, 1, "gui.amount.plus1");
        bindStep(15, Material.LIME_STAINED_GLASS_PANE, 16, "gui.amount.plus16");
        bindStep(16, Material.LIME_DYE, 64, "gui.amount.plus64");

        bindPreset(19, Material.STONE, 1, "gui.amount.set1");
        bindPreset(20, Material.IRON_INGOT, 16, "gui.amount.set16");
        bindPreset(21, Material.GOLD_INGOT, 64, "gui.amount.set64");
        bindPreset(22, Material.DIAMOND, 576, "gui.amount.set576");
        bindPreset(23, Material.NETHERITE_INGOT, 1728, "gui.amount.set1728");
        bindPreset(24, Material.EMERALD_BLOCK, max, "gui.amount.setmax");

        set(18, Icons.button(plugin, Material.BARRIER, "gui.cancel", List.of()));
        bind(18, getInventory().getItem(18), this::back);
        set(26, Icons.button(plugin, Material.LIME_DYE, "gui.confirm.action",
                List.of("gui.amount.confirm-lore"), "amount", String.valueOf(amount)));
        bind(26, getInventory().getItem(26), () -> {
            onConfirm.accept(amount);
            back();
        });
    }

    private void bindStep(int slot, Material material, int delta, String key) {
        ItemStack icon = Icons.of(material, plugin.messages().raw(key),
                List.of(plugin.messages().raw("gui.amount.click")));
        bind(slot, icon, () -> {
            amount = clamp(amount + delta);
            refresh();
        });
    }

    private void bindPreset(int slot, Material material, int value, String key) {
        ItemStack icon = Icons.of(material, plugin.messages().raw(key,
                        "amount", String.valueOf(clamp(value))),
                List.of(plugin.messages().raw("gui.amount.click")));
        bind(slot, icon, () -> {
            amount = clamp(value);
            refresh();
        });
    }

    private int clamp(int value) {
        return Math.max(1, Math.min(max, value));
    }
}

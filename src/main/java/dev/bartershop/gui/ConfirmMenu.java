package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Explicit confirmation screen for destructive actions. */
public final class ConfirmMenu extends Menu {

    private final String prompt;
    private final Runnable onConfirm;

    public ConfirmMenu(BarterShopPlugin plugin, Player player, Menu parent, String prompt, Runnable onConfirm) {
        super(plugin, player, parent);
        this.prompt = prompt;
        this.onConfirm = onConfirm;
    }

    @Override
    protected String titleKey() {
        return "gui.confirm.title";
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void render() {
        clear();
        set(4, Icons.of(Material.PAPER, plugin.messages().raw("gui.confirm.prompt"),
                GuiUtil.wrap(prompt, 40)));
        set(11, Icons.button(plugin, Material.LIME_DYE, "gui.confirm.yes",
                List.of("gui.confirm.yes-lore")));
        bind(11, getInventory().getItem(11), () -> {
            onConfirm.run();
        });
        set(15, Icons.button(plugin, Material.RED_DYE, "gui.confirm.no", List.of("gui.confirm.no-lore")));
        bind(15, getInventory().getItem(15), this::back);
        set(22, Icons.close(plugin));
        bind(22, getInventory().getItem(22), player::closeInventory);
    }
}

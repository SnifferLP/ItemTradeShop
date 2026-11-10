package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Short in-GUI explanation of the sign and chest entry point. */
public final class CreateShopInfoMenu extends Menu {

    public CreateShopInfoMenu(BarterShopPlugin plugin, Player player, Menu parent) {
        super(plugin, player, parent);
    }

    @Override
    protected String titleKey() {
        return "gui.create.title";
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void render() {
        clear();
        set(10, Icons.button(plugin, Material.OAK_SIGN, "gui.create.sign", List.of("gui.create.sign-lore1",
                "gui.create.sign-lore2", "gui.create.sign-lore3")));
        set(13, Icons.button(plugin, Material.CHEST, "gui.create.chest", List.of("gui.create.chest-lore1",
                "gui.create.chest-lore2", "gui.create.chest-lore3")));
        set(16, Icons.button(plugin, Material.LIME_DYE, "gui.create.gui", List.of("gui.create.gui-lore1",
                "gui.create.gui-lore2", "gui.create.gui-lore3")));

        ItemStack book = Icons.button(plugin, Material.BOOK, "gui.create.tips", List.of("gui.create.tip1",
                "gui.create.tip2", "gui.create.tip3", "gui.create.tip4"));
        set(22, book);

        set(18, Icons.back(plugin));
        bind(18, getInventory().getItem(18), this::back);
        set(26, Icons.close(plugin));
        bind(26, getInventory().getItem(26), player::closeInventory);
    }
}

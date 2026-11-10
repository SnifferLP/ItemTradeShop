package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.item.ItemData;
import dev.bartershop.util.ItemCodec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/** Display-item builders. Icons are always copies and never real inventory content. */
public final class Icons {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private Icons() {
    }

    public static ItemStack button(BarterShopPlugin plugin, Material material, String nameKey,
                                   List<String> loreKeys, String... placeholders) {
        List<String> lore = new ArrayList<>();
        for (String key : loreKeys) {
            lore.add(plugin.messages().raw(key, placeholders));
        }
        return of(material, plugin.messages().raw(nameKey, placeholders), lore);
    }

    public static ItemStack of(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material == null ? Material.STONE : material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(parse(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(parse(line));
            }
            meta.lore(lines);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack item(ItemData data, int amount, String name, List<String> lore) {
        ItemStack stack;
        try {
            stack = ItemCodec.icon(data);
        } catch (RuntimeException e) {
            stack = new ItemStack(Material.BARRIER);
        }
        stack = stack.clone();
        stack.setAmount(Math.max(1, Math.min(amount, Math.max(1, stack.getMaxStackSize()))));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(parse(name));
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(parse(line));
            }
            meta.lore(lines);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    public static ItemStack back(BarterShopPlugin plugin) {
        return button(plugin, Material.ARROW, "gui.back", List.of());
    }

    public static ItemStack close(BarterShopPlugin plugin) {
        return button(plugin, Material.BARRIER, "gui.close", List.of());
    }

    public static ItemStack previous(BarterShopPlugin plugin, int page) {
        return button(plugin, Material.SPECTRAL_ARROW, "gui.previous",
                List.of("gui.page"), "page", String.valueOf(page));
    }

    public static ItemStack next(BarterShopPlugin plugin, int page) {
        return button(plugin, Material.SPECTRAL_ARROW, "gui.next",
                List.of("gui.page"), "page", String.valueOf(page));
    }

    private static Component decorate(Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private static Component parse(String input) {
        try {
            return decorate(MINI.deserialize(input));
        } catch (RuntimeException e) {
            return decorate(Component.text(input.replaceAll("<[^>]+>", "")));
        }
    }
}

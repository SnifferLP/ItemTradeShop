package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Language;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Personal display preferences, currently the interface language. */
public final class PreferencesMenu extends Menu {

    public PreferencesMenu(BarterShopPlugin plugin, Player player, Menu parent) {
        super(plugin, player, parent);
    }

    @Override
    protected String titleKey() {
        return "gui.preferences.title";
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void render() {
        clear();
        Language current = plugin.languageFor(player.getUniqueId());
        set(4, Icons.of(Material.BOOK, plugin.messages().raw("gui.preferences.info"),
                List.of(plugin.messages().raw("gui.preferences.info-lore"))));

        set(13, Icons.of(Material.WRITABLE_BOOK,
                plugin.messages().raw("gui.preferences.language",
                        "language", current.nativeName()),
                List.of(plugin.messages().raw("gui.preferences.language-lore"),
                        plugin.messages().raw("gui.preferences.language-click"))));
        bind(13, getInventory().getItem(13), () -> {
            Language next = current.next();
            if (plugin.languages().setLanguage(player.getUniqueId(), next)) {
                Feedback.confirm(player);
                new PreferencesMenu(plugin, player, parent).open();
            } else {
                plugin.messages().send(player, "error.database");
                Feedback.error(player);
            }
        });

        set(18, Icons.back(plugin));
        bind(18, getInventory().getItem(18), this::back);
        set(26, Icons.close(plugin));
        bind(26, getInventory().getItem(26), player::closeInventory);
    }
}

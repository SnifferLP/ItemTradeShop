package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.ShopStats;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Persistent trade counters and a shortcut to recent activity. */
public final class StatsMenu extends Menu {

    private final Shop shop;

    public StatsMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent) {
        super(plugin, player, parent);
        this.shop = shop;
    }

    @Override
    protected String titleKey() {
        return "gui.stats.title";
    }

    @Override
    protected int size() {
        return 27;
    }

    @Override
    protected void render() {
        clear();
        ShopStats stats = shop.stats();
        List<String> lore = new ArrayList<>();
        lore.add(plugin.messages().raw("gui.stats.trades",
                "count", String.valueOf(stats.totalTrades())));
        lore.add(plugin.messages().raw("gui.stats.items-in",
                "count", String.valueOf(stats.itemsIn())));
        lore.add(plugin.messages().raw("gui.stats.items-out",
                "count", String.valueOf(stats.itemsOut())));
        lore.add(plugin.messages().raw("gui.stats.customers",
                "count", String.valueOf(stats.uniqueCustomers())));
        lore.add(stats.lastTradeAt() > 0
                ? plugin.messages().raw("gui.stats.last-trade",
                "time", GuiUtil.time(stats.lastTradeAt()))
                : plugin.messages().raw("gui.stats.no-trades"));
        set(13, Icons.of(Material.WRITABLE_BOOK, plugin.messages().raw("gui.stats.info"), lore));

        set(15, Icons.button(plugin, Material.CLOCK, "gui.stats.recent",
                List.of("gui.stats.recent-lore")));
        bind(15, getInventory().getItem(15),
                () -> HistoryMenu.open(plugin, player, shop.id(), shop.owner(), 0, this));

        set(18, Icons.back(plugin));
        bind(18, getInventory().getItem(18), this::back);
        set(26, Icons.close(plugin));
        bind(26, getInventory().getItem(26), player::closeInventory);
    }
}

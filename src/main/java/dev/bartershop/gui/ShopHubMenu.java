package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.Shop;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Central management menu for one shop. */
public final class ShopHubMenu extends Menu {

    private final Shop shop;
    private final boolean admin;

    public ShopHubMenu(BarterShopPlugin plugin, Player player, Shop shop, boolean admin, Menu parent) {
        super(plugin, player, parent);
        this.shop = shop;
        this.admin = admin;
    }

    @Override
    protected String titleKey() {
        return "gui.hub.title";
    }

    @Override
    protected Component title() {
        return Component.text(shop.name());
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        List<String> info = new ArrayList<>();
        info.add(plugin.messages().raw("gui.hub.info-owner", "owner", GuiUtil.safe(shop.ownerName())));
        info.add(plugin.messages().raw("gui.hub.info-location",
                "world", GuiUtil.safe(shop.primaryChest().world()),
                "x", String.valueOf(shop.primaryChest().x()),
                "y", String.valueOf(shop.primaryChest().y()),
                "z", String.valueOf(shop.primaryChest().z())));
        info.add(plugin.messages().raw("gui.hub.info-chests",
                "count", String.valueOf(shop.chestUnitCount()),
                "capacity", String.valueOf(shop.capacitySlots())));
        info.add(plugin.messages().raw(shop.enabled() ? "gui.shops.enabled" : "gui.shops.disabled"));
        if (shop.warningThreshold() > 0) {
            info.add(plugin.messages().raw("gui.hub.warning-threshold",
                    "count", String.valueOf(shop.warningThreshold())));
        }
        if (shop.sign() == null) {
            info.add(plugin.messages().raw("gui.hub.info-sign-none"));
        }
        if (plugin.maintenanceMode()) {
            info.add(plugin.messages().raw("gui.hub.maintenance"));
        }
        if (shop.orphaned()) {
            info.add(plugin.messages().raw("gui.hub.orphaned"));
        }
        if (admin && !shop.owner().equals(player.getUniqueId())) {
            info.add(plugin.messages().raw("gui.hub.admin-mode"));
        }
        set(4, Icons.of(Material.NAME_TAG, GuiUtil.safe(shop.name()), info));

        if (shop.orphaned()) {
            set(13, Icons.button(plugin, Material.BARRIER, "gui.hub.orphaned-title",
                    List.of("gui.hub.orphaned-lore", "gui.hub.orphaned-lore2")));
        }

        bind(19, Icons.button(plugin, Material.ITEM_FRAME, "gui.hub.offers",
                        List.of("gui.hub.offers-lore"),
                        "count", String.valueOf(shop.offers().size())),
                () -> new OfferListMenu(plugin, player, shop, this).open());

        bind(21, Icons.button(plugin, Material.CHEST, "gui.hub.stock",
                        List.of("gui.hub.stock-lore", "gui.hub.stock-lore2"),
                        "used", String.valueOf(shop.stock().slotsUsed()),
                        "capacity", String.valueOf(shop.capacitySlots())),
                () -> new StockMenu(plugin, player, shop, this).open());

        bind(23, Icons.button(plugin, Material.EMERALD, "gui.hub.proceeds",
                        List.of("gui.hub.proceeds-lore"),
                        "count", String.valueOf(shop.proceeds().totalItems())),
                () -> new ProceedsMenu(plugin, player, shop, this, 0).open());

        bind(25, Icons.button(plugin, Material.CHEST_MINECART, "gui.hub.chests",
                        List.of("gui.hub.chests-lore"),
                        "count", String.valueOf(shop.chestUnitCount())),
                () -> new ChestsMenu(plugin, player, shop, this).open());

        bind(30, Icons.button(plugin, Material.COMPARATOR, "gui.hub.settings",
                        List.of("gui.hub.settings-lore")),
                () -> new SettingsMenu(plugin, player, shop, admin, this).open());

        bind(32, Icons.button(plugin, Material.BOOK, "gui.hub.stats",
                        List.of("gui.hub.stats-lore"),
                        "trades", String.valueOf(shop.stats().totalTrades())),
                () -> new StatsMenu(plugin, player, shop, this).open());

        bind(34, Icons.button(plugin, Material.WRITABLE_BOOK, "gui.hub.history",
                        List.of("gui.hub.history-lore")),
                () -> HistoryMenu.open(plugin, player, shop.id(), shop.owner(), 0, this));

        bind(40, Icons.button(plugin, Material.ENDER_EYE, "gui.hub.preview",
                        List.of("gui.hub.preview-lore")),
                () -> new CustomerShopMenu(plugin, player, shop, this).open());

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }
}

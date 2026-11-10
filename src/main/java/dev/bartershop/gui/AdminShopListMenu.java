package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.Shop;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** All shops with owner and anchor status; opens the admin hub for each. */
public final class AdminShopListMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final int page;

    public AdminShopListMenu(BarterShopPlugin plugin, Player player, Menu parent, int page) {
        super(plugin, player, parent);
        this.page = Math.max(0, page);
    }

    @Override
    protected String titleKey() {
        return "gui.admin.shops-title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        List<Shop> shops = new ArrayList<>(plugin.shopManager().all());
        shops.sort(Comparator.comparing((Shop shop) -> shop.ownerName().toLowerCase())
                .thenComparing(shop -> shop.id().toString()));
        int pages = Math.max(1, (shops.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, shops.size());
        for (int i = start; i < end; i++) {
            Shop shop = shops.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.admin.shop-owner",
                    "owner", GuiUtil.safe(shop.ownerName())));
            lore.add(plugin.messages().raw("gui.admin.shop-location",
                    "world", GuiUtil.safe(shop.primaryChest().world()),
                    "x", String.valueOf(shop.primaryChest().x()),
                    "y", String.valueOf(shop.primaryChest().y()),
                    "z", String.valueOf(shop.primaryChest().z())));
            lore.add(plugin.messages().raw("gui.admin.shop-stock",
                    "used", String.valueOf(shop.stock().slotsUsed()),
                    "capacity", String.valueOf(shop.capacitySlots())));
            lore.add(plugin.messages().raw("gui.admin.shop-proceeds",
                    "items", String.valueOf(shop.proceeds().totalItems())));
            lore.add(plugin.messages().raw(shop.enabled() ? "gui.shops.enabled" : "gui.shops.disabled"));
            if (shop.orphaned()) {
                lore.add(plugin.messages().raw("gui.admin.shop-orphaned"));
            }
            ItemStack icon = Icons.of(shop.enabled() ? Material.CHEST : Material.TRAPPED_CHEST,
                    GuiUtil.safe(shop.name()), lore);
            int slot = 9 + (i - start);
            bind(slot, icon, () -> new ShopHubMenu(plugin, player, shop, true, this).open());
        }
        if (shops.isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "gui.admin.no-shops", List.of()));
        }
        if (current > 0) {
            set(48, Icons.previous(plugin, current));
            bind(48, getInventory().getItem(48),
                    () -> new AdminShopListMenu(plugin, player, parent, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, Icons.next(plugin, current + 2));
            bind(50, getInventory().getItem(50),
                    () -> new AdminShopListMenu(plugin, player, parent, current + 1).open());
        }
        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }
}

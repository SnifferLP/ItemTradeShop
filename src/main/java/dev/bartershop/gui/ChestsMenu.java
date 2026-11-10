package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.LocationKey;
import dev.bartershop.core.model.Shop;
import dev.bartershop.service.ShopManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Lists physical chest units and links or unlinks storage slots. */
public final class ChestsMenu extends Menu {

    private final Shop shop;

    public ChestsMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent) {
        super(plugin, player, parent);
        this.shop = shop;
    }

    @Override
    protected String titleKey() {
        return "gui.chests.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        List<List<LocationKey>> units = shop.chestUnits();
        for (int i = 0; i < units.size() && i < 18; i++) {
            List<LocationKey> unit = units.get(i);
            LocationKey anchor = unit.get(0);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.chests.location",
                    "world", GuiUtil.safe(anchor.world()),
                    "x", String.valueOf(anchor.x()),
                    "y", String.valueOf(anchor.y()),
                    "z", String.valueOf(anchor.z())));
            lore.add(plugin.messages().raw("gui.chests.slots",
                    "slots", String.valueOf(unit.size() > 1 ? 54 : 27)));
            lore.add(plugin.messages().raw(i == 0
                    ? "gui.chests.primary" : "gui.chests.unlink-hint"));
            int index = i;
            ItemStack icon = Icons.of(Material.CHEST, plugin.messages().raw(
                    "gui.chests.unit", "number", String.valueOf(i + 1)), lore);
            bind(9 + i, icon, () -> {
                if (index == 0) {
                    return;
                }
                new ConfirmMenu(plugin, player, this,
                        plugin.messages().raw("gui.chests.unlink-confirm"), () -> {
                            ShopManager.RelinkFailure failure = plugin.shopManager()
                                    .removeChest(player, shop, index);
                            if (failure == null) {
                                Feedback.success(player);
                                new ChestsMenu(plugin, player, shop, returnTo()).open();
                            } else {
                                Feedback.error(player);
                                plugin.messages().send(player, switch (failure) {
                                    case CAPACITY_TOO_SMALL -> "shop.chest-capacity";
                                    case LAST_CHEST -> "shop.last-chest";
                                    case LOCKED -> "trade.failed.busy";
                                    case DATABASE_ERROR -> "error.database";
                                    default -> "shop.chest-remove-failed";
                                });
                                refresh();
                            }
                        }).open();
            });
        }

        set(4, Icons.of(Material.CHEST_MINECART, plugin.messages().raw("gui.chests.info"),
                List.of(plugin.messages().raw("gui.chests.info-lore",
                                "used", String.valueOf(shop.stock().slotsUsed()),
                                "capacity", String.valueOf(shop.capacitySlots())),
                        plugin.messages().raw("gui.chests.info-lore2",
                                "max", String.valueOf(plugin.settings().maxChestsPerShop())))));

        set(47, Icons.button(plugin, Material.CHEST, "gui.chests.relink-primary",
                List.of("gui.chests.relink-primary-lore")));
        bind(47, getInventory().getItem(47), () -> plugin.signService().beginChestRelink(player, shop));

        set(49, Icons.button(plugin, Material.HOPPER, "gui.chests.add",
                List.of("gui.chests.add-lore")));
        bind(49, getInventory().getItem(49), () -> plugin.signService().beginChestAdd(player, shop));

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }

    private Menu returnTo() {
        return parent instanceof SettingsMenu ? parent.parentMenu() : parent;
    }
}

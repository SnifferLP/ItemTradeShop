package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.Shop;
import dev.bartershop.service.ShopManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/** Click a stack in your own inventory to deposit it into the shop's virtual stock. */
public final class DepositMenu extends Menu {

    private final Shop shop;

    public DepositMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent) {
        super(plugin, player, parent);
        this.shop = shop;
    }

    @Override
    protected String titleKey() {
        return "gui.deposit.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        set(4, Icons.button(plugin, Material.CHEST, "gui.deposit.capacity",
                List.of("gui.deposit.capacity-lore"),
                "used", String.valueOf(shop.stock().slotsUsed()),
                "capacity", String.valueOf(shop.capacitySlots()),
                "items", String.valueOf(shop.stock().totalItems())));
        List<String> instructions = List.of(
                plugin.messages().raw("gui.deposit.lore1"),
                plugin.messages().raw("gui.deposit.lore2"),
                plugin.messages().raw("gui.deposit.lore3"));
        set(22, Icons.of(Material.HOPPER, plugin.messages().raw("gui.deposit.info"), instructions));

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(49, Icons.close(plugin));
        bind(49, getInventory().getItem(49), player::closeInventory);
    }

    @Override
    public void onPlayerInventoryClick(int playerSlot, ItemStack clicked) {
        if (playerSlot < 0 || playerSlot >= 36 || clicked == null || clicked.getType().isAir()) {
            return;
        }
        ShopManager.StockResult result = plugin.shopManager().deposit(player, shop, playerSlot);
        switch (result.outcome()) {
            case SUCCESS -> plugin.messages().send(player, "stock.deposited",
                    "amount", String.valueOf(result.amount()), "item", GuiUtil.safe(result.itemName()));
            case STORAGE_FULL -> plugin.messages().send(player, "stock.full");
            case NOT_OWNER -> plugin.messages().send(player, "general.no-permission");
            case EMPTY_HAND -> plugin.messages().send(player, "stock.empty-hand");
            case INVALID_ITEM -> plugin.messages().send(player, "offer.invalid-item");
            case CURSOR_BUSY -> plugin.messages().send(player, "trade.failed.cursor");
            case DATABASE_ERROR -> plugin.messages().send(player, "error.database");
            case CHANGED -> plugin.messages().send(player, "error.inventory-changed");
            case LOCKED -> plugin.messages().send(player, "trade.failed.busy");
            default -> plugin.messages().send(player, "error.generic");
        }
        refresh();
    }
}

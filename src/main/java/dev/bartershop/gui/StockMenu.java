package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.StockStack;
import dev.bartershop.service.ShopManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** View, deposit and withdraw the items held in a shop's virtual stock. */
public final class StockMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final Shop shop;
    private final int page;
    private final Map<Integer, Integer> slotToStock = new HashMap<>();

    public StockMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent) {
        this(plugin, player, shop, parent, 0);
    }

    public StockMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent, int page) {
        super(plugin, player, parent);
        this.shop = shop;
        this.page = Math.max(0, page);
    }

    @Override
    protected String titleKey() {
        return "gui.stock.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        slotToStock.clear();
        int pages = Math.max(1, (shop.stock().stacks().size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, shop.stock().stacks().size());
        for (int i = start; i < end; i++) {
            StockStack stack = shop.stock().at(i);
            int slot = 9 + (i - start);
            slotToStock.put(slot, i);
            List<String> lore = List.of(
                    plugin.messages().raw("gui.stock.stack-amount", "amount", String.valueOf(stack.amount())),
                    plugin.messages().raw("gui.stock.stack-withdraw-all"),
                    plugin.messages().raw("gui.stock.stack-withdraw-one"));
            set(slot, Icons.item(stack.item(), 1, GuiUtil.safe(stack.item().prettyName()), lore));
        }
        if (shop.stock().stacks().isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "gui.stock.empty",
                    List.of("gui.stock.empty-lore")));
        }

        set(4, Icons.button(plugin, Material.CHEST, "gui.stock.capacity",
                List.of("gui.stock.capacity-lore", "gui.stock.capacity-lore2"),
                "used", String.valueOf(shop.stock().slotsUsed()),
                "capacity", String.valueOf(shop.capacitySlots()),
                "items", String.valueOf(shop.stock().totalItems())));

        set(46, Icons.button(plugin, Material.HOPPER, "gui.stock.deposit",
                List.of("gui.stock.deposit-lore")));
        bind(46, getInventory().getItem(46), () -> new DepositMenu(plugin, player, shop, this).open());

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);

        if (current > 0) {
            set(48, Icons.previous(plugin, current));
            bind(48, getInventory().getItem(48),
                    () -> new StockMenu(plugin, player, shop, parent, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, Icons.next(plugin, current + 2));
            bind(50, getInventory().getItem(50),
                    () -> new StockMenu(plugin, player, shop, parent, current + 1).open());
        }
    }

    @Override
    public void handleClick(int slot, ClickType click) {
        Integer index = slotToStock.get(slot);
        if (index != null) {
            StockStack stack = shop.stock().at(index);
            int amount = click == ClickType.RIGHT ? 1 : stack.amount();
            withdraw(index, amount);
            return;
        }
        super.handleClick(slot, click);
    }

    private void withdraw(int index, int amount) {
        ShopManager.StockResult result = plugin.shopManager().withdraw(player, shop, index, amount);
        switch (result.outcome()) {
            case SUCCESS -> plugin.messages().send(player, "stock.withdrew",
                    "amount", String.valueOf(result.amount()), "item", GuiUtil.safe(result.itemName()));
            case INVENTORY_FULL -> plugin.messages().send(player, "proceeds.inventory-full");
            case NOT_OWNER -> plugin.messages().send(player, "general.no-permission");
            case DATABASE_ERROR -> plugin.messages().send(player, "error.database");
            case CURSOR_BUSY -> plugin.messages().send(player, "trade.failed.cursor");
            case CHANGED -> plugin.messages().send(player, "error.inventory-changed");
            case LOCKED -> plugin.messages().send(player, "trade.failed.busy");
            default -> plugin.messages().send(player, "error.generic");
        }
        refresh();
    }
}

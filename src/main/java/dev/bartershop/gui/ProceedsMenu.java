package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.StockStack;
import dev.bartershop.service.ProceedsService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Shows queued owner earnings and collects them with partial-fit support. */
public final class ProceedsMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final Shop shop;
    private final int page;

    public ProceedsMenu(BarterShopPlugin plugin, Player player, Shop shop, Menu parent, int page) {
        super(plugin, player, parent);
        this.shop = shop;
        this.page = Math.max(0, page);
    }

    @Override
    protected String titleKey() {
        return "gui.proceeds.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        List<StockStack> stacks = shop.proceeds().stacks();
        int pages = Math.max(1, (stacks.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, stacks.size());
        for (int i = start; i < end; i++) {
            StockStack stack = stacks.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.proceeds.stack-lore",
                    "amount", String.valueOf(stack.amount())));
            lore.add(plugin.messages().raw("gui.proceeds.collect-hint"));
            int slot = 9 + (i - start);
            ItemStack icon = Icons.item(stack.item(), 1, GuiUtil.safe(stack.item().prettyName()), lore);
            bind(slot, icon, this::collectAll);
        }
        if (stacks.isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "gui.proceeds.empty", List.of()));
        }

        List<String> collectLore = new ArrayList<>();
        collectLore.add(plugin.messages().raw("gui.proceeds.collect-lore1"));
        collectLore.add(plugin.messages().raw("gui.proceeds.collect-lore2",
                "items", String.valueOf(shop.proceeds().totalItems())));
        set(49, Icons.of(Material.EMERALD, plugin.messages().raw("gui.proceeds.collect"), collectLore));
        bind(49, getInventory().getItem(49), this::collectAll);

        set(47, Icons.button(plugin, Material.WRITABLE_BOOK, "gui.proceeds.history",
                List.of("gui.proceeds.history-lore")));
        bind(47, getInventory().getItem(47),
                () -> HistoryMenu.open(plugin, player, shop.id(), shop.owner(), 0, this));

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);

        if (current > 0) {
            set(48, Icons.previous(plugin, current));
            bind(48, getInventory().getItem(48),
                    () -> new ProceedsMenu(plugin, player, shop, parent, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, Icons.next(plugin, current + 2));
            bind(50, getInventory().getItem(50),
                    () -> new ProceedsMenu(plugin, player, shop, parent, current + 1).open());
        }
    }

    private void collectAll() {
        ProceedsService.CollectionResult result = plugin.proceedsService().collectShop(player, shop);
        String key = switch (result.outcome()) {
            case SUCCESS -> null;
            case NOTHING_PENDING -> "gui.proceeds.empty";
            case INVENTORY_FULL -> "proceeds.inventory-full";
            case NOT_OWNER -> "general.no-permission";
            case BUSY -> "trade.failed.busy";
            case CHANGED -> "error.inventory-changed";
            case DATABASE_ERROR -> "error.database";
        };
        if (key != null) {
            plugin.messages().send(player, key);
        }
        refresh();
    }
}

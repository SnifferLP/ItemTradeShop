package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.StockStack;
import dev.bartershop.core.proceeds.ProceedsStorage;
import dev.bartershop.service.ProceedsService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Owner mailbox for items recovered from deleted shops. */
public final class MailboxMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final ProceedsStorage mailbox;
    private final int page;

    public MailboxMenu(BarterShopPlugin plugin, Player player, Menu parent,
                       ProceedsStorage mailbox, int page) {
        super(plugin, player, parent);
        this.mailbox = mailbox;
        this.page = Math.max(0, page);
    }

    @Override
    protected String titleKey() {
        return "gui.mailbox.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        List<StockStack> stacks = mailbox.stacks();
        int pages = Math.max(1, (stacks.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, stacks.size());
        for (int i = start; i < end; i++) {
            StockStack stack = stacks.get(i);
            int slot = 9 + (i - start);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.mailbox.item-lore",
                    "amount", String.valueOf(stack.amount())));
            ItemStack icon = Icons.item(stack.item(), 1, GuiUtil.safe(stack.item().prettyName()), lore);
            set(slot, icon);
        }
        if (stacks.isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "mailbox.empty", List.of()));
        }

        List<String> collectLore = new ArrayList<>();
        collectLore.add(plugin.messages().raw("gui.mailbox.collect-lore1"));
        collectLore.add(plugin.messages().raw("gui.mailbox.collect-lore2"));
        set(49, Icons.of(Material.HOPPER, plugin.messages().raw("gui.mailbox.collect"), collectLore));
        bind(49, getInventory().getItem(49), this::collect);

        set(47, Icons.button(plugin, Material.WRITABLE_BOOK, "gui.mailbox.history",
                List.of("gui.mailbox.history-lore")));
        bind(47, getInventory().getItem(47), () ->
                HistoryMenu.open(plugin, player, null, player.getUniqueId(), 0, this));

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);

        if (current > 0) {
            set(48, Icons.previous(plugin, current));
            bind(48, getInventory().getItem(48), () -> new MailboxMenu(plugin, player, parent,
                    mailbox, current - 1).open());
        }
        if (current + 1 < pages) {
            set(50, Icons.next(plugin, current + 2));
            bind(50, getInventory().getItem(50), () -> new MailboxMenu(plugin, player, parent,
                    mailbox, current + 1).open());
        }
    }

    private void collect() {
        ProceedsService.CollectionResult result = plugin.proceedsService().collectMailbox(player);
        String key = switch (result.outcome()) {
            case SUCCESS -> null;
            case NOTHING_PENDING -> "mailbox.empty";
            case INVENTORY_FULL -> "proceeds.inventory-full";
            case NOT_OWNER -> "general.no-permission";
            case BUSY -> "trade.failed.busy";
            case CHANGED -> "error.inventory-changed";
            case DATABASE_ERROR -> "error.database";
        };
        if (key != null) {
            plugin.messages().send(player, key);
        }
        reload();
    }

    private void reload() {
        if (!player.isOnline()) {
            return;
        }
        plugin.database().callAsync(repository -> repository.loadMailbox(player.getUniqueId()))
                .thenAccept(updated -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        new MailboxMenu(plugin, player, parent, updated, page).open();
                    }
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("Could not reload mailbox: " + throwable.getMessage());
                    return null;
                });
    }
}

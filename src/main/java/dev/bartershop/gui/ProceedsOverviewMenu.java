package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.Shop;
import dev.bartershop.core.model.StockStack;
import dev.bartershop.service.ProceedsService;
import dev.bartershop.storage.StorageRecords.PendingSummary;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** One place to review and collect proceeds from every owned shop. */
public final class ProceedsOverviewMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final List<PendingSummary> summaries;
    private final int page;

    private ProceedsOverviewMenu(BarterShopPlugin plugin, Player player, Menu parent,
                                 List<PendingSummary> summaries, int page) {
        super(plugin, player, parent);
        this.summaries = summaries;
        this.page = Math.max(0, page);
    }

    public static void open(BarterShopPlugin plugin, Player player, Menu parent) {
        player.closeInventory();
        plugin.database().callAsync(repository ->
                        repository.findPendingSummaries(player.getUniqueId()))
                .thenAccept(summaries -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        new ProceedsOverviewMenu(plugin, player, parent, summaries, 0).open();
                    }
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("Could not load proceeds overview: " + throwable.getMessage());
                    Bukkit.getScheduler().runTask(plugin,
                            () -> plugin.messages().send(player, "error.database"));
                    return null;
                });
    }

    @Override
    protected String titleKey() {
        return "gui.proceeds-overview.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        int pages = Math.max(1, (summaries.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        int start = current * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, summaries.size());
        int total = 0;
        for (PendingSummary summary : summaries) {
            total += summary.totalItems();
        }
        for (int i = start; i < end; i++) {
            PendingSummary summary = summaries.get(i);
            int slot = 9 + (i - start);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.proceeds-overview.amount",
                    "count", String.valueOf(summary.totalItems())));
            for (StockStack stack : summary.items()) {
                if (lore.size() >= 5) {
                    lore.add(plugin.messages().raw("gui.proceeds-overview.more"));
                    break;
                }
                lore.add(plugin.messages().raw("gui.proceeds-overview.line",
                        "amount", String.valueOf(stack.amount()),
                        "item", GuiUtil.safe(stack.item().prettyName())));
            }
            lore.add(plugin.messages().raw(summary.shopId() == null
                    ? "gui.proceeds-overview.mailbox-hint"
                    : "gui.proceeds-overview.open-hint"));
            ItemStack icon = Icons.of(summary.shopId() == null
                            ? Material.ENDER_CHEST : Material.CHEST,
                    GuiUtil.safe(label(summary)), lore);
            bind(slot, icon, () -> openSummary(summary));
        }
        if (summaries.isEmpty()) {
            set(22, Icons.button(plugin, Material.BARRIER, "gui.proceeds-overview.empty",
                    List.of("gui.proceeds-overview.empty-lore")));
        }

        set(4, Icons.of(Material.EMERALD, plugin.messages().raw("gui.proceeds-overview.info"),
                List.of(plugin.messages().raw("gui.proceeds-overview.info-lore",
                        "total", String.valueOf(total),
                        "shops", String.valueOf(summaries.size())))));

        set(49, Icons.button(plugin, Material.EMERALD_BLOCK, "gui.proceeds-overview.collect",
                List.of("gui.proceeds-overview.collect-lore")));
        bind(49, getInventory().getItem(49), this::collectAll);

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);

        if (current > 0) {
            set(46, Icons.previous(plugin, current));
            bind(46, getInventory().getItem(46), () ->
                    new ProceedsOverviewMenu(plugin, player, parent, summaries, current - 1).open());
        }
        if (current + 1 < pages) {
            set(52, Icons.next(plugin, current + 2));
            bind(52, getInventory().getItem(52), () ->
                    new ProceedsOverviewMenu(plugin, player, parent, summaries, current + 1).open());
        }
    }

    /** Shop names are user data; recovered or deleted entries use a translated label. */
    private String label(PendingSummary summary) {
        if (summary.shopId() == null) {
            return plugin.messages().raw("gui.proceeds-overview.mailbox-label");
        }
        return summary.label().isBlank()
                ? plugin.messages().raw("gui.proceeds-overview.deleted-label") : summary.label();
    }

    private void openSummary(PendingSummary summary) {
        if (summary.shopId() == null) {
            plugin.database().callAsync(repository ->
                            repository.loadMailbox(player.getUniqueId()))
                    .thenAccept(mailbox -> Bukkit.getScheduler().runTask(plugin, () -> {
                        if (player.isOnline()) {
                            new MailboxMenu(plugin, player, this, mailbox, 0).open();
                        }
                    }))
                    .exceptionally(throwable -> {
                        plugin.messages().send(player, "error.database");
                        return null;
                    });
            return;
        }
        Shop shop = plugin.shopManager().byId(summary.shopId()).orElse(null);
        if (shop == null) {
            plugin.messages().send(player, "error.shop-missing");
            return;
        }
        new ProceedsMenu(plugin, player, shop, this, 0).open();
    }

    private void collectAll() {
        ProceedsService.CollectAllResult result = plugin.proceedsService().collectAll(player);
        if (result.collected() > 0) {
            Feedback.success(player);
        } else if (result.outcome() != ProceedsService.CollectionOutcome.NOTHING_PENDING) {
            Feedback.error(player);
        }
        open(plugin, player, parent);
    }
}

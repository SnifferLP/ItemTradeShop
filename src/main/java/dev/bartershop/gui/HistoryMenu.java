package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.storage.StorageRecords.HistoryEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Read-only transaction history, loaded asynchronously page by page. */
public final class HistoryMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final UUID shopId;
    private final UUID ownerId;
    private final List<HistoryEntry> entries;
    private final int total;
    private final int page;

    private HistoryMenu(BarterShopPlugin plugin, Player player, Menu parent, UUID shopId, UUID ownerId,
                        List<HistoryEntry> entries, int total, int page) {
        super(plugin, player, parent);
        this.shopId = shopId;
        this.ownerId = ownerId;
        this.entries = entries;
        this.total = total;
        this.page = Math.max(0, page);
    }

    public static void open(BarterShopPlugin plugin, Player player, UUID shopId, UUID ownerId,
                            int page, Menu parent) {
        int safePage = Math.max(0, page);
        plugin.database().callAsync(repository -> {
            List<HistoryEntry> entries = shopId != null
                    ? repository.loadHistory(shopId, PAGE_SIZE, safePage * PAGE_SIZE)
                    : repository.loadHistoryForOwner(ownerId, PAGE_SIZE, safePage * PAGE_SIZE);
            int total = shopId != null
                    ? repository.countHistory(shopId)
                    : repository.countHistoryForOwner(ownerId);
            return new HistoryData(entries, total);
        }).thenAccept(data -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                new HistoryMenu(plugin, player, parent, shopId, ownerId, data.entries, data.total, safePage).open();
            }
        })).exceptionally(throwable -> {
            plugin.getLogger().warning("Could not load history: " + throwable.getMessage());
            Bukkit.getScheduler().runTask(plugin, () -> plugin.messages().send(player, "error.database"));
            return null;
        });
    }

    @Override
    protected String titleKey() {
        return "gui.history.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int current = Math.min(page, pages - 1);
        for (int i = 0; i < entries.size() && i < PAGE_SIZE; i++) {
            HistoryEntry entry = entries.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.history.time", "time", GuiUtil.time(entry.createdAt())));
            if (entry.actorName() != null && !entry.actorName().isBlank()) {
                lore.add(plugin.messages().raw("gui.history.actor",
                        "actor", GuiUtil.safe(entry.actorName())));
            }
            lore.addAll(GuiUtil.wrap(GuiUtil.safe(entry.description()), 44));
            ItemStack icon = Icons.of(iconFor(entry.type()),
                    plugin.messages().raw(historyTypeKey(entry.type())), lore);
            set(9 + i, icon);
        }
        if (entries.isEmpty()) {
            set(22, Icons.button(plugin, Material.PAPER, "gui.history.empty", List.of()));
        }
        if (current > 0) {
            set(48, Icons.previous(plugin, current));
            bind(48, getInventory().getItem(48),
                    () -> open(plugin, player, shopId, ownerId, current - 1, parent));
        }
        if (current + 1 < pages) {
            set(50, Icons.next(plugin, current + 2));
            bind(50, getInventory().getItem(50),
                    () -> open(plugin, player, shopId, ownerId, current + 1, parent));
        }
        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }

    private static String historyTypeKey(String type) {
        return switch (type) {
            case "TRADE" -> "gui.history.type.trade";
            case "COLLECT" -> "gui.history.type.collect";
            case "STOCK" -> "gui.history.type.stock";
            case "RECOVERY" -> "gui.history.type.recovery";
            case "DELETE" -> "gui.history.type.delete";
            case "TRANSFER" -> "gui.history.type.transfer";
            case "CONFIG" -> "gui.history.type.config";
            default -> "gui.history.type.other";
        };
    }

    private static Material iconFor(String type) {
        return switch (type) {
            case "TRADE" -> Material.GOLD_INGOT;
            case "COLLECT" -> Material.EMERALD;
            case "STOCK" -> Material.CHEST;
            case "RECOVERY" -> Material.ENDER_CHEST;
            case "DELETE" -> Material.BARRIER;
            case "TRANSFER" -> Material.PLAYER_HEAD;
            default -> Material.PAPER;
        };
    }

    private record HistoryData(List<HistoryEntry> entries, int total) {
    }
}

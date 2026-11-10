package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.storage.StorageRecords.AuditEntry;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** Read-only audit trail of administrative and ownership actions. */
public final class AuditMenu extends Menu {

    private static final int PAGE_SIZE = 36;
    private final List<AuditEntry> entries;
    private final int total;
    private final int page;

    private AuditMenu(BarterShopPlugin plugin, Player player, Menu parent,
                      List<AuditEntry> entries, int total, int page) {
        super(plugin, player, parent);
        this.entries = entries;
        this.total = total;
        this.page = Math.max(0, page);
    }

    public static void open(BarterShopPlugin plugin, Player player, Menu parent, int page) {
        int safePage = Math.max(0, page);
        plugin.database().callAsync(repository -> new AuditData(
                        repository.loadAudit(PAGE_SIZE, safePage * PAGE_SIZE),
                        repository.countAudit()))
                .thenAccept(data -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        new AuditMenu(plugin, player, parent, data.entries, data.total, safePage).open();
                    }
                }))
                .exceptionally(throwable -> {
                    plugin.getLogger().warning("Could not load audit log: " + throwable.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> plugin.messages().send(player, "error.database"));
                    return null;
                });
    }

    @Override
    protected String titleKey() {
        return "gui.audit.title";
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
            AuditEntry entry = entries.get(i);
            List<String> lore = new ArrayList<>();
            lore.add(plugin.messages().raw("gui.audit.actor",
                    "actor", GuiUtil.safe(entry.actorName() == null ? "system" : entry.actorName())));
            lore.add(plugin.messages().raw("gui.audit.time", "time", GuiUtil.time(entry.createdAt())));
            lore.addAll(GuiUtil.wrap(GuiUtil.safe(entry.details()), 44));
            set(9 + i, Icons.of(Material.PAPER, "<white>" + GuiUtil.safe(entry.action()), lore));
        }
        if (entries.isEmpty()) {
            set(22, Icons.button(plugin, Material.PAPER, "gui.audit.empty", List.of()));
        }
        if (current > 0) {
            set(48, Icons.previous(plugin, current));
            bind(48, getInventory().getItem(48), () -> open(plugin, player, parent, current - 1));
        }
        if (current + 1 < pages) {
            set(50, Icons.next(plugin, current + 2));
            bind(50, getInventory().getItem(50), () -> open(plugin, player, parent, current + 1));
        }
        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }

    private record AuditData(List<AuditEntry> entries, int total) {
    }
}

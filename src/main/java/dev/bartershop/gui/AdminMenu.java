package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** Admin entry point: shop overview, maintenance, anchor validation and audit log. */
public final class AdminMenu extends Menu {

    public AdminMenu(BarterShopPlugin plugin, Player player, Menu parent) {
        super(plugin, player, parent);
    }

    @Override
    protected String titleKey() {
        return "gui.admin.title";
    }

    @Override
    protected int size() {
        return 54;
    }

    @Override
    protected void render() {
        clear();
        set(4, Icons.button(plugin, Material.NETHER_STAR, "gui.admin.info",
                List.of("gui.admin.info-lore"),
                "count", String.valueOf(plugin.shopManager().all().size())));

        set(20, Icons.button(plugin, Material.CHEST, "gui.admin.shops",
                List.of("gui.admin.shops-lore")));
        bind(20, getInventory().getItem(20),
                () -> new AdminShopListMenu(plugin, player, this, 0).open());

        set(22, Icons.button(plugin, Material.COMPASS, "gui.admin.scan",
                List.of("gui.admin.scan-lore")));
        bind(22, getInventory().getItem(22), () -> {
            int count = plugin.shopManager().scanOrphans();
            plugin.messages().send(player, "admin.scan-done", "count", String.valueOf(count));
            plugin.shopManager().refreshSigns();
            Feedback.confirm(player);
            refresh();
        });

        set(24, Icons.button(plugin, Material.WRITABLE_BOOK, "gui.admin.audit",
                List.of("gui.admin.audit-lore")));
        bind(24, getInventory().getItem(24), () -> AuditMenu.open(plugin, player, this, 0));

        set(30, Icons.of(plugin.maintenanceMode() ? Material.REDSTONE_BLOCK : Material.LEVER,
                plugin.messages().raw("gui.admin.maintenance",
                        "state", Messages.rich(plugin.messages().raw(plugin.maintenanceMode()
                                ? "gui.admin.maintenance-on" : "gui.admin.maintenance-off"))),
                List.of(plugin.messages().raw("gui.admin.maintenance-lore"),
                        plugin.messages().raw("gui.admin.maintenance-click"))));
        bind(30, getInventory().getItem(30), () -> {
            plugin.setMaintenanceMode(!plugin.maintenanceMode(), player.getName());
            Feedback.confirm(player);
            refresh();
        });

        set(32, Icons.button(plugin, Material.ENDER_CHEST, "gui.admin.recovery",
                List.of("gui.admin.recovery-lore1", "gui.admin.recovery-lore2",
                        "gui.admin.recovery-lore3", "gui.admin.recovery-lore4")));

        set(34, Icons.button(plugin, Material.BOOK, "gui.admin.rules",
                List.of("gui.admin.rules-lore1", "gui.admin.rules-lore2",
                        "gui.admin.rules-lore3", "gui.admin.rules-lore4")));

        set(45, Icons.back(plugin));
        bind(45, getInventory().getItem(45), this::back);
        set(53, Icons.close(plugin));
        bind(53, getInventory().getItem(53), player::closeInventory);
    }
}

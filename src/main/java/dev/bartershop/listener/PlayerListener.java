package dev.bartershop.listener;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.Shop;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Join-time pending-proceeds notifications and per-player session cleanup. */
public final class PlayerListener implements Listener {

    private final BarterShopPlugin plugin;

    public PlayerListener(BarterShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> plugin.notificationService().onJoin(event.getPlayer()), 20L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!event.getPlayer().isOnline()) {
                return;
            }
            int orphaned = 0;
            for (Shop shop : plugin.shopManager().ownedBy(event.getPlayer().getUniqueId())) {
                if (shop.orphaned()) {
                    orphaned++;
                }
                // Owners who were offline when stock dropped below their
                // threshold receive the warning once they return.
                plugin.shopManager().checkStockWarnings(shop);
            }
            if (orphaned > 0) {
                plugin.messages().send(event.getPlayer(), "shop.orphaned-join",
                        "count", String.valueOf(orphaned));
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.signService().onQuit(event.getPlayer());
        // Per-player caches are created lazily, so they can be dropped on quit
        // instead of accumulating one entry for every player that ever joined.
        plugin.tradeService().forget(event.getPlayer().getUniqueId());
        plugin.proceedsService().forget(event.getPlayer().getUniqueId());
        plugin.notificationService().forget(event.getPlayer().getUniqueId());
        if (plugin.menuListener() != null) {
            plugin.menuListener().forget(event.getPlayer().getUniqueId());
        }
    }
}

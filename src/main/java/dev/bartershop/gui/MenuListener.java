package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.util.RateLimiter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single choke point for all GUI input. Every click, drag, number-key swap,
 * double-click, shift-click, offhand swap and drop is cancelled before the
 * menu action runs. Menus contain display copies only, so even a bypass could
 * not move a real shop item.
 */
public final class MenuListener implements Listener {

    private final BarterShopPlugin plugin;
    private final RateLimiter limiter = new RateLimiter();
    private final Map<UUID, Long> lastClick = new ConcurrentHashMap<>();

    public MenuListener(BarterShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) {
            return;
        }
        if (!debounce(player)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlot() >= 0 && event.getRawSlot() < topSize) {
            menu.handleClick(event.getRawSlot(), event.getClick());
        } else if (event.getClickedInventory() != null
                && event.getClickedInventory().equals(player.getInventory())) {
            menu.onPlayerInventoryClick(event.getSlot(), event.getCurrentItem());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
            event.setResult(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Menu menu) {
            menu.onClose();
        }
    }

    /** Drops debounce state when a player leaves; the limiter keeps its spam budget. */
    public void forget(UUID player) {
        lastClick.remove(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    private boolean debounce(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastClick.get(player.getUniqueId());
        long minimumGap = Math.max(0L, plugin.settings().clickDebounceMillis());
        // Do not refresh the timestamp for a rejected click: a player clicking
        // slightly too fast must not be able to starve their own next action.
        if (previous != null && now - previous < minimumGap) {
            return false;
        }
        if (!limiter.allow(player.getUniqueId(), "gui",
                plugin.settings().maxClicksPerSecond(), 1_000L)) {
            return false;
        }
        lastClick.put(player.getUniqueId(), now);
        return true;
    }
}

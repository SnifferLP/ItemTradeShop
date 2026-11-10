package dev.bartershop.listener;

import dev.bartershop.BarterShopPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/**
 * Rewrites shop sign text when its chunk becomes visible again. The periodic
 * refresh covers unloaded chunks eventually, but a player walking up to a sign
 * must never see text from before the last offer or stock change.
 */
public final class SignDisplayListener implements Listener {

    private final BarterShopPlugin plugin;

    public SignDisplayListener(BarterShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (plugin.signService() != null && !event.isNewChunk()) {
            plugin.signService().refreshChunk(event.getChunk());
        }
    }
}

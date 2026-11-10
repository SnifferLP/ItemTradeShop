package dev.bartershop.listener;

import dev.bartershop.BarterShopPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;

/** Routes sign edits into the setup workflow or blocks edits to registered signs. */
public final class SignListener implements Listener {

    private final BarterShopPlugin plugin;

    public SignListener(BarterShopPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        plugin.signService().onSignChange(event);
    }
}

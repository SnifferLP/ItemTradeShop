package dev.bartershop.gui;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.config.Messages;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Base for every GUI. Menus hold only display copies; no menu slot is a real
 * item container. All clicks are handled and cancelled by {@link MenuListener}.
 */
public abstract class Menu implements InventoryHolder {

    protected final BarterShopPlugin plugin;
    protected final Player player;
    protected final Menu parent;
    private final Map<Integer, Runnable> actions = new HashMap<>();
    private Inventory inventory;

    protected Menu(BarterShopPlugin plugin, Player player, Menu parent) {
        this.plugin = plugin;
        this.player = player;
        this.parent = parent;
    }

    protected abstract String titleKey();

    protected abstract int size();

    protected abstract void render();

    protected Component title() {
        return plugin.messages().component(titleKey());
    }

    public final void open() {
        try (Messages.Context ignored = plugin.messages().contextFor(player)) {
            inventory = Bukkit.createInventory(this, boundedSize(), title());
            safeRender();
            player.openInventory(inventory);
        }
    }

    public final void refresh() {
        if (inventory == null) {
            return;
        }
        if (player.getOpenInventory().getTopInventory().getHolder() != this) {
            return;
        }
        try (Messages.Context ignored = plugin.messages().contextFor(player)) {
            safeRender();
            player.updateInventory();
        }
    }

    /**
     * Bukkit only accepts 9..54 slot inventories in multiples of nine. A layout
     * bug must never throw out of an inventory event and leave a blank screen.
     */
    private int boundedSize() {
        int clamped = Math.max(9, Math.min(54, size()));
        int remainder = clamped % 9;
        return remainder == 0 ? clamped : clamped + (9 - remainder);
    }

    private void safeRender() {
        try {
            render();
        } catch (RuntimeException failure) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Failed to render " + getClass().getSimpleName() + " for " + player.getName(), failure);
            if (inventory != null) {
                inventory.clear();
            }
            actions.clear();
            player.sendMessage(plugin.messages().component("error.generic"));
        }
    }

    @Override
    public final Inventory getInventory() {
        return inventory;
    }

    public void handleClick(int slot) {
        handleClick(slot, org.bukkit.event.inventory.ClickType.LEFT);
    }

    public void handleClick(int slot, org.bukkit.event.inventory.ClickType click) {
        Runnable action = actions.get(slot);
        if (action == null) {
            return;
        }
        try {
            action.run();
        } catch (RuntimeException failure) {
            // A failed action must never look like a successful one, and must
            // never escape into the server's inventory event handling.
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Menu action failed in " + getClass().getSimpleName() + " slot " + slot
                            + " for " + player.getName(), failure);
            player.sendMessage(plugin.messages().component("error.generic"));
            refresh();
        }
    }

    /** Called for clicks in the player's own inventory; default ignores them. */
    public void onPlayerInventoryClick(int playerSlot, ItemStack clicked) {
    }

    public void onClose() {
    }

    public void back() {
        if (parent != null) {
            parent.open();
        } else {
            player.closeInventory();
        }
    }

    protected final void clear() {
        if (inventory != null) {
            inventory.clear();
        }
        actions.clear();
    }

    protected final void set(int slot, ItemStack item) {
        if (inventory != null && slot >= 0 && slot < inventory.getSize()) {
            inventory.setItem(slot, item);
        }
    }

    protected final void bind(int slot, ItemStack item, Runnable action) {
        set(slot, item);
        if (action != null) {
            actions.put(slot, action);
        }
    }

    protected final Menu parentMenu() {
        return parent;
    }
}

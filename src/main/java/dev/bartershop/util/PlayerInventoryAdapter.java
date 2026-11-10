package dev.bartershop.util;

import dev.bartershop.core.inv.InventoryModel;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the pure {@link InventoryModel} to the Bukkit player inventory.
 * Only slots 0-35 (main inventory and hotbar) participate in trades and
 * collections, so armour and offhand items are never silently consumed.
 */
public final class PlayerInventoryAdapter {

    public static final int STORAGE_SLOTS = 36;

    private PlayerInventoryAdapter() {
    }

    public static InventoryModel modelOf(Player player) {
        InventoryModel model = InventoryModel.empty(STORAGE_SLOTS);
        for (int slot = 0; slot < STORAGE_SLOTS; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack == null || stack.getType().isAir()) {
                continue;
            }
            model.set(slot, new InventoryModel.HeldItem(ItemCodec.of(stack), stack.getAmount()));
        }
        return model;
    }

    /**
     * Applies the changed slots of a plan to the real inventory. Fails (without
     * writing anything) if another plugin or a player interaction changed one of
     * the affected slots between planning and application.
     */
    public static void apply(Player player, InventoryModel before, InventoryModel after) {
        List<Integer> changed = after.changedSlots(before);
        for (int slot : changed) {
            ItemStack current = player.getInventory().getItem(slot);
            InventoryModel.HeldItem expected = before.get(slot);
            if (!same(current, expected)) {
                throw new InventoryChangedException("inventory slot " + slot + " changed during transaction");
            }
        }
        // Resolve every destination before writing anything. A corrupt stored
        // item therefore aborts the transaction without a partially applied diff.
        Map<Integer, ItemStack> resolved = new HashMap<>();
        for (int slot : changed) {
            InventoryModel.HeldItem held = after.get(slot);
            resolved.put(slot, held == null ? null : ItemCodec.toStack(held.item(), held.amount()));
        }
        for (int slot : changed) {
            player.getInventory().setItem(slot, resolved.get(slot));
        }
        player.updateInventory();
    }

    /** Force-restores the before-state of changed slots (used on failure). */
    public static void restore(Player player, InventoryModel before, InventoryModel after) {
        Map<Integer, ItemStack> resolved = new HashMap<>();
        List<Integer> changed = after.changedSlots(before);
        for (int slot : changed) {
            InventoryModel.HeldItem held = before.get(slot);
            resolved.put(slot, held == null ? null : ItemCodec.toStack(held.item(), held.amount()));
        }
        for (int slot : changed) {
            player.getInventory().setItem(slot, resolved.get(slot));
        }
        player.updateInventory();
    }

    public static boolean same(ItemStack stack, InventoryModel.HeldItem held) {
        if (stack == null || stack.getType().isAir()) {
            return held == null;
        }
        if (held == null || stack.getAmount() != held.amount()) {
            return false;
        }
        try {
            return ItemCodec.of(stack).signature().equals(held.item().signature());
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static boolean cursorEmpty(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        return cursor == null || cursor.getType().isAir();
    }

    public static class InventoryChangedException extends RuntimeException {
        public InventoryChangedException(String message) {
            super(message);
        }
    }
}

package dev.bartershop.util;

import dev.bartershop.core.item.ItemData;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Base64;

/**
 * Converts between live {@link ItemStack}s and the portable {@link ItemData}
 * record. The whole serialised item is normalised to amount 1 and hashed, so
 * exact matching covers every component the server knows about: damage,
 * enchantments, custom name, lore, potion data, custom model data, persistent
 * data and future components.
 */
public final class ItemCodec {

    private ItemCodec() {
    }

    public static ItemData of(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            throw new IllegalArgumentException("cannot encode air");
        }
        ItemStack single = stack.clone();
        single.setAmount(1);
        byte[] bytes = single.serializeAsBytes();
        String serialized = Base64.getEncoder().encodeToString(bytes);
        String signature = ItemData.sha256(bytes);
        int maxStack = Math.max(1, Math.min(127, stack.getMaxStackSize()));
        return new ItemData(stack.getType().name(), signature, serialized, maxStack, displayName(stack));
    }

    public static ItemStack toStack(ItemData data, int amount) {
        if (amount < 1) {
            throw new IllegalArgumentException("amount must be positive");
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(data.serialized());
            ItemStack stack = ItemStack.deserializeBytes(bytes);
            if (stack == null || stack.getType().isAir()) {
                throw new IllegalArgumentException("stored item is air");
            }
            if (amount > stack.getMaxStackSize()) {
                throw new IllegalArgumentException("amount " + amount + " exceeds max stack "
                        + stack.getMaxStackSize() + " for " + data.material());
            }
            stack.setAmount(amount);
            return stack;
        } catch (RuntimeException e) {
            throw new ItemDecodeException("Could not decode stored item " + data.material()
                    + " (" + data.signature().substring(0, Math.min(8, data.signature().length()))
                    + "): " + e.getMessage(), e);
        }
    }

    /** Returns a safe display icon even when a stored blob can no longer be decoded after an update. */
    public static ItemStack icon(ItemData data) {
        try {
            return toStack(data, 1);
        } catch (RuntimeException e) {
            ItemStack fallback = new ItemStack(Material.BARRIER);
            ItemMeta meta = fallback.getItemMeta();
            meta.displayName(net.kyori.adventure.text.Component.text("Undecodable item"));
            fallback.setItemMeta(meta);
            return fallback;
        }
    }

    public static String displayName(ItemStack stack) {
        if (stack == null || stack.getType().isAir()) {
            return "Air";
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        String lower = stack.getType().name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static final class ItemDecodeException extends RuntimeException {
        public ItemDecodeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}

package dev.bartershop.core.item;

/**
 * Matching rules used by trades, stock and payment validation.
 *
 * <p>Exact matching compares the full item signature, which is derived from
 * the complete serialised item (material, damage, enchantments, custom name,
 * lore, potion data, custom model data, persistent data and every other item
 * component). Loose matching compares only the material and is used exclusively
 * when a shop owner explicitly enables "accept any variant" on an offer.</p>
 */
public final class ItemMatcher {

    private ItemMatcher() {
    }

    public static boolean matches(ItemData candidate, ItemData wanted, boolean exact) {
        if (candidate == null || wanted == null) {
            return false;
        }
        return exact ? candidate.sameMetadata(wanted) : candidate.sameMaterial(wanted);
    }
}

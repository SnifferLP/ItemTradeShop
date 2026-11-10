package dev.bartershop.core.item;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Portable description of an item type including its full metadata.
 *
 * <p>The {@code serialized} field is a normalised (amount = 1) item blob
 * produced by the platform adapter. The {@code signature} is the SHA-256 of
 * those bytes, used for exact metadata matching and indexed comparisons.
 * No Bukkit types are referenced here so that matching, stock and transaction
 * logic are unit-testable without a running server.</p>
 *
 * @param material      Bukkit material name, used for loose matching and display
 * @param signature     hex SHA-256 of {@code serialized}
 * @param serialized    base64 item blob normalised to amount 1
 * @param maxStackSize  maximum stack size reported by the item
 * @param displayName   plain-text display name captured at configuration time, may be null
 */
public record ItemData(
        String material,
        String signature,
        String serialized,
        int maxStackSize,
        String displayName
) {

    public ItemData {
        Objects.requireNonNull(material, "material");
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(serialized, "serialized");
        if (material.isBlank()) {
            throw new IllegalArgumentException("material must not be blank");
        }
        if (signature.isBlank()) {
            throw new IllegalArgumentException("signature must not be blank");
        }
        if (serialized.isBlank()) {
            throw new IllegalArgumentException("serialized item must not be blank");
        }
        if (maxStackSize < 1 || maxStackSize > 127) {
            throw new IllegalArgumentException("invalid max stack size: " + maxStackSize);
        }
        if (displayName != null && displayName.length() > 200) {
            displayName = displayName.substring(0, 200);
        }
    }

    public static ItemData of(String material, String serialized, int maxStackSize, String displayName) {
        return new ItemData(material, sha256(serialized), serialized, maxStackSize, displayName);
    }

    /** Used by tests and repository fixtures; the blob never reaches a server. */
    public static ItemData test(String material, String signature, int maxStackSize, String displayName) {
        return new ItemData(material, signature, Base64.getEncoder()
                .encodeToString((material + "|" + signature).getBytes(StandardCharsets.UTF_8)),
                maxStackSize, displayName);
    }

    public String name() {
        return material;
    }

    /** Human-readable name for GUIs and messages. */
    public String prettyName() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        String lower = material.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public boolean sameMaterial(ItemData other) {
        return other != null && material.equals(other.material);
    }

    public boolean sameMetadata(ItemData other) {
        return other != null && material.equals(other.material) && signature.equals(other.signature);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String sha256(byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

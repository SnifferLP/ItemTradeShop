package dev.bartershop.util;

import dev.bartershop.BarterShopPlugin;
import dev.bartershop.core.model.LocationKey;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bukkit-specific location, container and persistent-data helpers. */
public final class LocationUtil {

    private LocationUtil() {
    }

    public static LocationKey key(Block block) {
        return new LocationKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static LocationKey key(org.bukkit.Location location) {
        return new LocationKey(location.getWorld().getName(),
                location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public static Block block(LocationKey key) {
        World world = resolveWorld(key.world());
        if (world == null) {
            return null;
        }
        return world.getBlockAt(key.x(), key.y(), key.z());
    }

    /** True when the world exists and the chunk is already loaded. */
    public static boolean isLoaded(LocationKey key) {
        World world = resolveWorld(key.world());
        return world != null && world.isChunkLoaded(key.x() >> 4, key.z() >> 4);
    }

    /** World names are stored lower-case; resolve them case-insensitively. */
    public static World resolveWorld(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        World exact = Bukkit.getWorld(name);
        if (exact != null) {
            return exact;
        }
        for (World world : Bukkit.getWorlds()) {
            if (world.getName().equalsIgnoreCase(name)) {
                return world;
            }
        }
        return null;
    }

    public static org.bukkit.Location toLocation(LocationKey key) {
        World world = resolveWorld(key.world());
        if (world == null) {
            return null;
        }
        return new org.bukkit.Location(world, key.x() + 0.5, key.y() + 0.5, key.z() + 0.5);
    }

    public static boolean isChest(Block block) {
        return block != null
                && (block.getType() == Material.CHEST || block.getType() == Material.TRAPPED_CHEST);
    }

    public static boolean isSign(Block block) {
        return block != null && block.getState() instanceof Sign;
    }

    /** Returns the primary and, for a double chest, partner block locations. */
    public static List<LocationKey> chestBlocks(Block clicked) {
        List<LocationKey> locations = new ArrayList<>();
        if (!isChest(clicked)) {
            return locations;
        }
        locations.add(key(clicked));
        if (clicked.getState() instanceof Chest chest) {
            Inventory inventory = chest.getBlockInventory();
            if (inventory instanceof DoubleChestInventory doubleChest) {
                Inventory left = doubleChest.getLeftSide();
                Inventory right = doubleChest.getRightSide();
                if (left != null && left.getLocation() != null) {
                    LocationKey leftKey = key(left.getLocation());
                    if (!locations.contains(leftKey)) {
                        locations.add(leftKey);
                    }
                }
                if (right != null && right.getLocation() != null) {
                    LocationKey rightKey = key(right.getLocation());
                    if (!locations.contains(rightKey)) {
                        locations.add(rightKey);
                    }
                }
            }
        }
        return locations;
    }

    public static int chestCapacity(Block chest) {
        if (!(chest.getState() instanceof Chest state)) {
            return 0;
        }
        return state.getBlockInventory().getSize();
    }

    public static boolean chestIsEmpty(Block chest) {
        if (!(chest.getState() instanceof Chest state)) {
            return false;
        }
        Inventory inventory = state.getBlockInventory();
        for (var stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    /** The block that holds a sign up; protected so the sign cannot be dropped indirectly. */
    public static LocationKey signSupport(Block signBlock) {
        BlockData data = signBlock.getBlockData();
        if (data instanceof WallSign wallSign) {
            return key(signBlock.getRelative(wallSign.getFacing().getOppositeFace()));
        }
        return key(signBlock.getRelative(BlockFace.DOWN));
    }

    /** Returns the chest a sign is attached to, or null. */
    public static Block attachedChest(Block signBlock) {
        BlockData data = signBlock.getBlockData();
        Block attached = data instanceof WallSign wallSign
                ? signBlock.getRelative(wallSign.getFacing().getOppositeFace())
                : signBlock.getRelative(BlockFace.DOWN);
        return isChest(attached) ? attached : null;
    }

    public static String signLine(Block signBlock, int index) {
        if (!(signBlock.getState() instanceof Sign sign)) {
            return "";
        }
        try {
            return PlainTextComponentSerializer.plainText().serialize(sign.getSide(Side.FRONT).line(index)).trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    public static void setSignLine(Block signBlock, int index, String text) {
        if (!(signBlock.getState() instanceof Sign sign)) {
            return;
        }
        sign.getSide(Side.FRONT).line(index, net.kyori.adventure.text.Component.text(text));
        sign.update(true, false);
    }

    /**
     * Writes the complete sign in one state update, skipping lines that already
     * match. The periodic refresh runs for every shop, so avoiding redundant
     * block updates keeps it cheap and stops needless client sign packets.
     */
    public static void writeSignLines(Block signBlock, String[] lines) {
        if (!(signBlock.getState() instanceof Sign sign)) {
            return;
        }
        boolean changed = false;
        for (int i = 0; i < lines.length && i < 4; i++) {
            String current = PlainTextComponentSerializer.plainText()
                    .serialize(sign.getSide(Side.FRONT).line(i)).trim();
            if (!current.equals(lines[i])) {
                sign.getSide(Side.FRONT).line(i, net.kyori.adventure.text.Component.text(lines[i]));
                changed = true;
            }
        }
        if (changed) {
            sign.update(true, false);
        }
    }

    public static UUID shopIdAt(BarterShopPlugin plugin, Block block) {
        if (block == null || !(block.getState() instanceof org.bukkit.block.TileState tile)) {
            return null;
        }
        PersistentDataContainer pdc = tile.getPersistentDataContainer();
        String raw = pdc.get(plugin.shopKey(), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static void tagShopBlock(BarterShopPlugin plugin, Block block, UUID shopId) {
        if (block == null || !(block.getState() instanceof org.bukkit.block.TileState tile)) {
            return;
        }
        tile.getPersistentDataContainer().set(plugin.shopKey(), PersistentDataType.STRING, shopId.toString());
        tile.update(true, false);
    }

    public static void untagShopBlock(BarterShopPlugin plugin, Block block) {
        if (block == null || !(block.getState() instanceof org.bukkit.block.TileState tile)) {
            return;
        }
        tile.getPersistentDataContainer().remove(plugin.shopKey());
        tile.update(true, false);
    }

    public static String describe(LocationKey key) {
        if (key == null) {
            return "unlinked";
        }
        return key.world() + " " + key.x() + ", " + key.y() + ", " + key.z();
    }
}

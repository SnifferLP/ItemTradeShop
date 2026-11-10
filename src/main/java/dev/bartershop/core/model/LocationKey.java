package dev.bartershop.core.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable, Bukkit-free identifier for a block position. World names are
 * stored rather than World objects so the value survives restarts and can be
 * persisted without serialising server objects.
 */
public record LocationKey(String world, int x, int y, int z) {

    public LocationKey {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()) {
            throw new IllegalArgumentException("world name must not be blank");
        }
        world = world.toLowerCase(Locale.ROOT);
    }

    public static LocationKey parse(String world, int x, int y, int z) {
        return new LocationKey(world, x, y, z);
    }

    public String asString() {
        return world + ":" + x + ":" + y + ":" + z;
    }

    public boolean sameWorld(LocationKey other) {
        return other != null && world.equals(other.world);
    }

    @Override
    public String toString() {
        return asString();
    }
}

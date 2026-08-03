package dev.cgp.coppergolempatch.memory;

import org.bukkit.Material;

import java.util.Set;

/**
 * What a golem last observed in one chest. {@code firstItemType} is whichever
 * item occupies the first non-empty slot (what a golem would actually pick up
 * from a copper chest); {@code allItemTypes} is every distinct item type
 * present anywhere in the chest, since vanilla matches a deposit target by
 * "empty, or contains this item type" against the whole chest, not just its
 * first slot.
 */
public record MemoryEntry(ChestKey key, Material firstItemType, Set<Material> allItemTypes, long lastSeenEpochMillis) {

    public MemoryEntry(ChestKey key, Material firstItemType, Set<Material> allItemTypes, long lastSeenEpochMillis) {
        this.key = key;
        this.firstItemType = firstItemType;
        this.allItemTypes = Set.copyOf(allItemTypes);
        this.lastSeenEpochMillis = lastSeenEpochMillis;
    }

    public boolean isEmpty() {
        return firstItemType == Material.AIR;
    }

    public boolean contains(Material type) {
        return allItemTypes.contains(type);
    }

    public boolean isStale(long ttlSeconds, long nowEpochMillis) {
        return nowEpochMillis - lastSeenEpochMillis > ttlSeconds * 1000L;
    }
}

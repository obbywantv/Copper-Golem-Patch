package dev.cgp.coppergolempatch.memory;

import org.bukkit.Material;

public record UnplaceableEntry(Material itemType, long markedAtEpochMillis) {

    public boolean isStale(long ttlSeconds, long nowEpochMillis) {
        return nowEpochMillis - markedAtEpochMillis > ttlSeconds * 1000L;
    }
}

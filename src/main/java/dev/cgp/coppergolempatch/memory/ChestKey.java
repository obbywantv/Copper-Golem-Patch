package dev.cgp.coppergolempatch.memory;

import org.bukkit.block.Block;

import java.util.UUID;

public record ChestKey(UUID worldId, int x, int y, int z) {

    public static ChestKey of(Block block) {
        return new ChestKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
    }
}

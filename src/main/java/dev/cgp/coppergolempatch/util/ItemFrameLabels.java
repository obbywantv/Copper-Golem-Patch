package dev.cgp.coppergolempatch.util;

import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.LinkedHashSet;
import java.util.Set;

public final class ItemFrameLabels {

    private static final BlockFace[] FACES = {
            BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST, BlockFace.UP, BlockFace.DOWN
    };

    private ItemFrameLabels() {
    }

    public static Set<Material> labelsOn(Block chestBlock) {
        Set<Material> labels = new LinkedHashSet<>();
        for (Block part : chestParts(chestBlock)) {
            collectOnBlock(part, labels);
        }
        return labels;
    }

    public static Set<Block> chestParts(Block chestBlock) {
        Set<Block> parts = new LinkedHashSet<>();
        parts.add(chestBlock);
        if (chestBlock.getState() instanceof Chest chest) {
            InventoryHolder holder = chest.getInventory().getHolder();
            if (holder instanceof DoubleChest doubleChest) {
                addHolderBlock(doubleChest.getLeftSide(), parts);
                addHolderBlock(doubleChest.getRightSide(), parts);
            }
        }
        return parts;
    }

    private static void addHolderBlock(InventoryHolder holder, Set<Block> parts) {
        if (holder instanceof Chest chest) {
            parts.add(chest.getBlock());
        }
    }

    private static void collectOnBlock(Block chestBlock, Set<Material> labels) {
        Set<Chunk> chunks = new LinkedHashSet<>();
        chunks.add(chestBlock.getChunk());
        for (BlockFace face : FACES) {
            chunks.add(chestBlock.getRelative(face).getChunk());
        }

        for (Chunk chunk : chunks) {
            if (!chunk.isLoaded()) {
                continue;
            }
            for (Entity entity : chunk.getEntities()) {
                if (!(entity instanceof ItemFrame frame)) {
                    continue;
                }
                if (!isAttachedToChest(frame, chestBlock)) {
                    continue;
                }
                ItemStack item = frame.getItem();
                if (item != null && item.getType() != Material.AIR) {
                    labels.add(item.getType());
                }
            }
        }
    }

    private static boolean isAttachedToChest(ItemFrame frame, Block chestBlock) {
        Block frameBlock = frame.getLocation().getBlock();
        if (sameBlock(frameBlock.getRelative(frame.getAttachedFace()), chestBlock)) {
            return true;
        }
        BlockFace facing = frame.getFacing();
        return facing != null
                && facing.isCartesian()
                && sameBlock(frameBlock.getRelative(facing.getOppositeFace()), chestBlock);
    }

    private static boolean sameBlock(Block a, Block b) {
        return a.getWorld().equals(b.getWorld())
                && a.getX() == b.getX()
                && a.getY() == b.getY()
                && a.getZ() == b.getZ();
    }
}

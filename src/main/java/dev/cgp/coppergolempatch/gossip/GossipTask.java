package dev.cgp.coppergolempatch.gossip;

import dev.cgp.coppergolempatch.config.PluginConfig;
import dev.cgp.coppergolempatch.memory.ChestMemory;
import dev.cgp.coppergolempatch.memory.GolemMemoryCache;
import dev.cgp.coppergolempatch.memory.UnplaceableRegistry;
import dev.cgp.coppergolempatch.util.ItemFrameLabels;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Periodically merges the chest memory of CopperGolems that wander close to
 * each other, simulating a swarm passing knowledge along by proximity rather
 * than through an instant, server-wide registry. Also re-checks each golem's
 * give-up marks against its known chests: a golem that is currently
 * empty-handed never gets to re-evaluate deposit chests on its own (vanilla
 * only proposes those while carrying an item), so without this periodic
 * sweep a freed-up chest would only be noticed once the TTL expires.
 *
 * All reads/mutations of a golem's memory go through GolemMemoryCache rather
 * than the codec directly, so a golem with several neighbours in range gets
 * its PersistentDataContainer decoded/encoded at most once per pass instead
 * of once per neighbour - this is also where the cache's batched writes
 * actually reach disk (see GolemMemoryCache.flush).
 */
public final class GossipTask implements Runnable {

    private final Supplier<PluginConfig> config;
    private final GolemMemoryCache cache;

    public GossipTask(Supplier<PluginConfig> config, GolemMemoryCache cache) {
        this.config = config;
        this.cache = cache;
    }

    @Override
    public void run() {
        if (!config.get().enabled()) {
            return;
        }
        List<CopperGolem> loaded = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            List<CopperGolem> golems = new ArrayList<>(world.getEntitiesByClass(CopperGolem.class));
            loaded.addAll(golems);
            for (CopperGolem golem : golems) {
                revalidateUnplaceable(golem);
            }
        }
        if (config.get().gossipEnabled()) {
            gossipAll();
        }
        cache.flush(loaded);
    }

    private void gossipAll() {
        double radiusSquared = config.get().gossipRadius() * config.get().gossipRadius();
        int maxEntries = config.get().maxEntriesPerGolem();

        for (World world : Bukkit.getWorlds()) {
            List<CopperGolem> golems = new ArrayList<>(world.getEntitiesByClass(CopperGolem.class));
            if (golems.size() < 2) {
                continue;
            }
            for (int i = 0; i < golems.size(); i++) {
                for (int j = i + 1; j < golems.size(); j++) {
                    CopperGolem a = golems.get(i);
                    CopperGolem b = golems.get(j);
                    if (a.getLocation().distanceSquared(b.getLocation()) <= radiusSquared) {
                        gossip(a, b, maxEntries);
                    }
                }
            }
        }
    }

    private void gossip(CopperGolem a, CopperGolem b, int maxEntries) {
        ChestMemory memoryA = cache.getMemory(a);
        ChestMemory memoryB = cache.getMemory(b);
        ChestMemory mergedMemory = ChestMemory.mergeFreshestWins(memoryA, memoryB, maxEntries);
        cache.putMemory(a, mergedMemory);
        cache.putMemory(b, mergedMemory);

        UnplaceableRegistry unplaceableA = cache.getUnplaceable(a);
        UnplaceableRegistry unplaceableB = cache.getUnplaceable(b);
        UnplaceableRegistry mergedUnplaceable = UnplaceableRegistry.mergeFreshestWins(unplaceableA, unplaceableB, maxEntries);
        cache.putUnplaceable(a, mergedUnplaceable);
        cache.putUnplaceable(b, mergedUnplaceable);
    }

    private void revalidateUnplaceable(CopperGolem golem) {
        UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
        if (unplaceable.asMap().isEmpty()) {
            return;
        }
        ChestMemory memory = cache.getMemory(golem);
        boolean changed = false;
        for (Material itemType : List.copyOf(unplaceable.asMap().keySet())) {
            if (hasReachableSpotFor(golem, memory, itemType)) {
                changed |= unplaceable.clear(itemType);
            }
        }
        if (changed) {
            cache.markUnplaceableDirty(golem);
        }
    }

    private boolean hasReachableSpotFor(CopperGolem golem, ChestMemory memory, Material itemType) {
        World world = golem.getWorld();
        UUID worldId = world.getUID();
        return memory.asMap().keySet().stream()
                .filter(key -> key.worldId().equals(worldId))
                .map(key -> world.getBlockAt(key.x(), key.y(), key.z()))
                .filter(block -> !isCopperChest(block.getType()))
                .map(Block::getState)
                .filter(Container.class::isInstance)
                .map(Container.class::cast)
                .anyMatch(container -> acceptsItem(container, itemType));
    }

    private boolean acceptsItem(Container container, Material itemType) {
        if (config.get().itemFrameLabelsEnabled()) {
            Set<Material> labels = ItemFrameLabels.labelsOn(container.getBlock());
            if (!labels.isEmpty() && !labels.contains(itemType)) {
                return false;
            }
        }
        boolean sawAnyItem = false;
        for (ItemStack stack : container.getInventory().getContents()) {
            if (stack != null && stack.getType() != Material.AIR) {
                sawAnyItem = true;
                if (stack.getType() == itemType) {
                    return true;
                }
            }
        }
        return !sawAnyItem;
    }

    private boolean isCopperChest(Material material) {
        return material.name().endsWith("COPPER_CHEST");
    }
}

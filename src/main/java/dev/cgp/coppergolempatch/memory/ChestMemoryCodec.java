package dev.cgp.coppergolempatch.memory;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

public final class ChestMemoryCodec {

    private static final String NAMESPACE = "coppergolempatch";
    private static final NamespacedKey WORLD_KEY = new NamespacedKey(NAMESPACE, "world");
    private static final NamespacedKey X_KEY = new NamespacedKey(NAMESPACE, "x");
    private static final NamespacedKey Y_KEY = new NamespacedKey(NAMESPACE, "y");
    private static final NamespacedKey Z_KEY = new NamespacedKey(NAMESPACE, "z");
    private static final NamespacedKey ITEM_KEY = new NamespacedKey(NAMESPACE, "item");
    private static final NamespacedKey ALL_ITEMS_KEY = new NamespacedKey(NAMESPACE, "all_items");
    private static final NamespacedKey SEEN_AT_KEY = new NamespacedKey(NAMESPACE, "seen_at");
    private static final NamespacedKey MARKED_AT_KEY = new NamespacedKey(NAMESPACE, "marked_at");

    private final NamespacedKey memoryListKey;
    private final NamespacedKey unplaceableListKey;
    private final Logger logger;

    public ChestMemoryCodec(Plugin plugin, Logger logger) {
        this.memoryListKey = new NamespacedKey(plugin, "chest_memory");
        this.unplaceableListKey = new NamespacedKey(plugin, "unplaceable_items");
        this.logger = logger;
    }

    public void write(PersistentDataContainer golemPdc, ChestMemory memory) {
        List<PersistentDataContainer> list = new ArrayList<>(memory.size());
        for (MemoryEntry entry : memory.asMap().values()) {
            PersistentDataContainer entryContainer = golemPdc.getAdapterContext().newPersistentDataContainer();
            entryContainer.set(WORLD_KEY, PersistentDataType.STRING, entry.key().worldId().toString());
            entryContainer.set(X_KEY, PersistentDataType.INTEGER, entry.key().x());
            entryContainer.set(Y_KEY, PersistentDataType.INTEGER, entry.key().y());
            entryContainer.set(Z_KEY, PersistentDataType.INTEGER, entry.key().z());
            entryContainer.set(ITEM_KEY, PersistentDataType.STRING, entry.firstItemType().name());
            List<String> allItemNames = entry.allItemTypes().stream().map(Material::name).toList();
            entryContainer.set(ALL_ITEMS_KEY, PersistentDataType.LIST.strings(), allItemNames);
            entryContainer.set(SEEN_AT_KEY, PersistentDataType.LONG, entry.lastSeenEpochMillis());
            list.add(entryContainer);
        }
        golemPdc.set(memoryListKey, PersistentDataType.LIST.dataContainers(), list);
    }

    public ChestMemory read(PersistentDataContainer golemPdc) {
        List<PersistentDataContainer> list = golemPdc.getOrDefault(memoryListKey, PersistentDataType.LIST.dataContainers(), List.of());
        ChestMemory memory = new ChestMemory();
        for (PersistentDataContainer entryContainer : list) {
            MemoryEntry entry = decodeEntry(entryContainer);
            if (entry != null) {
                memory.recordObservation(entry.key(), entry.firstItemType(), entry.allItemTypes(),
                        entry.lastSeenEpochMillis(), Integer.MAX_VALUE);
            }
        }
        return memory;
    }

    public void writeUnplaceable(PersistentDataContainer golemPdc, UnplaceableRegistry registry) {
        List<PersistentDataContainer> list = new ArrayList<>(registry.size());
        for (UnplaceableEntry entry : registry.asMap().values()) {
            PersistentDataContainer entryContainer = golemPdc.getAdapterContext().newPersistentDataContainer();
            entryContainer.set(ITEM_KEY, PersistentDataType.STRING, entry.itemType().name());
            entryContainer.set(MARKED_AT_KEY, PersistentDataType.LONG, entry.markedAtEpochMillis());
            list.add(entryContainer);
        }
        golemPdc.set(unplaceableListKey, PersistentDataType.LIST.dataContainers(), list);
    }

    public UnplaceableRegistry readUnplaceable(PersistentDataContainer golemPdc) {
        List<PersistentDataContainer> list = golemPdc.getOrDefault(unplaceableListKey, PersistentDataType.LIST.dataContainers(), List.of());
        UnplaceableRegistry registry = new UnplaceableRegistry();
        for (PersistentDataContainer entryContainer : list) {
            UnplaceableEntry entry = decodeUnplaceableEntry(entryContainer);
            if (entry != null) {
                registry.markUnplaceable(entry.itemType(), entry.markedAtEpochMillis(), Integer.MAX_VALUE);
            }
        }
        return registry;
    }

    private MemoryEntry decodeEntry(PersistentDataContainer entryContainer) {
        String worldId = entryContainer.get(WORLD_KEY, PersistentDataType.STRING);
        Integer x = entryContainer.get(X_KEY, PersistentDataType.INTEGER);
        Integer y = entryContainer.get(Y_KEY, PersistentDataType.INTEGER);
        Integer z = entryContainer.get(Z_KEY, PersistentDataType.INTEGER);
        String item = entryContainer.get(ITEM_KEY, PersistentDataType.STRING);
        Long seenAt = entryContainer.get(SEEN_AT_KEY, PersistentDataType.LONG);
        if (worldId == null || x == null || y == null || z == null || item == null || seenAt == null) {
            logger.warning("[Copper Golem Patch] Skipping malformed chest memory entry");
            return null;
        }
        Material firstItemType = parseMaterial(item);
        if (firstItemType == null) {
            return null;
        }
        List<String> allItemNames = entryContainer.getOrDefault(ALL_ITEMS_KEY, PersistentDataType.LIST.strings(), List.of());
        Set<Material> allItemTypes = new LinkedHashSet<>();
        for (String name : allItemNames) {
            Material material = parseMaterial(name);
            if (material != null) {
                allItemTypes.add(material);
            }
        }
        if (allItemTypes.isEmpty() && firstItemType != Material.AIR) {
            // Older/malformed entries without all_items - fall back to first-only.
            allItemTypes.add(firstItemType);
        }
        ChestKey key = new ChestKey(UUID.fromString(worldId), x, y, z);
        return new MemoryEntry(key, firstItemType, allItemTypes, seenAt);
    }

    private UnplaceableEntry decodeUnplaceableEntry(PersistentDataContainer entryContainer) {
        String item = entryContainer.get(ITEM_KEY, PersistentDataType.STRING);
        Long markedAt = entryContainer.get(MARKED_AT_KEY, PersistentDataType.LONG);
        if (item == null || markedAt == null) {
            logger.warning("[Copper Golem Patch] Skipping malformed unplaceable-item entry");
            return null;
        }
        Material material = parseMaterial(item);
        if (material == null) {
            return null;
        }
        return new UnplaceableEntry(material, markedAt);
    }

    private Material parseMaterial(String name) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            logger.warning("[Copper Golem Patch] Skipping entry with unknown material: " + name);
            return null;
        }
    }
}

package dev.cgp.coppergolempatch.memory;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class ChestMemory {

    private final Map<ChestKey, MemoryEntry> entries = new LinkedHashMap<>();

    public static ChestMemory of(Map<ChestKey, MemoryEntry> initial) {
        ChestMemory memory = new ChestMemory();
        memory.entries.putAll(initial);
        return memory;
    }

    public void recordObservation(ChestKey key, Material firstItemType, Set<Material> allItemTypes,
                                   long nowEpochMillis, int maxEntries) {
        entries.put(key, new MemoryEntry(key, firstItemType, allItemTypes, nowEpochMillis));
        evictIfOverCapacity(maxEntries);
    }

    public Optional<MemoryEntry> get(ChestKey key) {
        return Optional.ofNullable(entries.get(key));
    }

    public Map<ChestKey, MemoryEntry> asMap() {
        return Collections.unmodifiableMap(entries);
    }

    public int size() {
        return entries.size();
    }

    public static ChestMemory mergeFreshestWins(ChestMemory a, ChestMemory b, int maxEntries) {
        Map<ChestKey, MemoryEntry> merged = new LinkedHashMap<>(a.entries);
        for (Map.Entry<ChestKey, MemoryEntry> e : b.entries.entrySet()) {
            MemoryEntry existing = merged.get(e.getKey());
            if (existing == null || e.getValue().lastSeenEpochMillis() > existing.lastSeenEpochMillis()) {
                merged.put(e.getKey(), e.getValue());
            }
        }
        ChestMemory result = ChestMemory.of(merged);
        result.evictIfOverCapacity(maxEntries);
        return result;
    }

    private void evictIfOverCapacity(int maxEntries) {
        while (entries.size() > maxEntries) {
            ChestKey stalest = null;
            long oldest = Long.MAX_VALUE;
            for (MemoryEntry entry : entries.values()) {
                if (entry.lastSeenEpochMillis() < oldest) {
                    oldest = entry.lastSeenEpochMillis();
                    stalest = entry.key();
                }
            }
            if (stalest == null) {
                break;
            }
            entries.remove(stalest);
        }
    }
}

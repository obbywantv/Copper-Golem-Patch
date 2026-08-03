package dev.cgp.coppergolempatch.memory;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GossipMergeTest {

    private static final UUID WORLD = UUID.randomUUID();

    private static ChestKey key(int x) {
        return new ChestKey(WORLD, x, 64, 0);
    }

    private static void record(ChestMemory memory, ChestKey key, Material firstItemType, long now, int maxEntries) {
        memory.recordObservation(key, firstItemType, Set.of(firstItemType), now, maxEntries);
    }

    @Test
    void mergeUnionsDisjointKeysFromBothGolems() {
        ChestMemory a = new ChestMemory();
        record(a, key(0), Material.DIAMOND, 1000L, 10);
        ChestMemory b = new ChestMemory();
        record(b, key(1), Material.GOLD_INGOT, 1000L, 10);

        ChestMemory merged = ChestMemory.mergeFreshestWins(a, b, 10);

        assertEquals(2, merged.size());
        assertEquals(Material.DIAMOND, merged.get(key(0)).orElseThrow().firstItemType());
        assertEquals(Material.GOLD_INGOT, merged.get(key(1)).orElseThrow().firstItemType());
    }

    @Test
    void mergeKeepsFreshestEntryOnConflict() {
        ChestMemory a = new ChestMemory();
        record(a, key(0), Material.DIAMOND, 5000L, 10);
        ChestMemory b = new ChestMemory();
        record(b, key(0), Material.AIR, 9000L, 10);

        ChestMemory merged = ChestMemory.mergeFreshestWins(a, b, 10);

        assertEquals(1, merged.size());
        assertEquals(Material.AIR, merged.get(key(0)).orElseThrow().firstItemType());
        assertEquals(9000L, merged.get(key(0)).orElseThrow().lastSeenEpochMillis());
    }

    @Test
    void mergeEvictsStalestEntriesWhenResultExceedsCapacity() {
        ChestMemory a = new ChestMemory();
        record(a, key(0), Material.DIAMOND, 1000L, 10);
        record(a, key(1), Material.EMERALD, 2000L, 10);
        ChestMemory b = new ChestMemory();
        record(b, key(2), Material.GOLD_INGOT, 3000L, 10);

        ChestMemory merged = ChestMemory.mergeFreshestWins(a, b, 2);

        assertEquals(2, merged.size());
        assertTrue(merged.get(key(0)).isEmpty(), "stalest entry should not survive the merge cap");
        assertTrue(merged.get(key(1)).isPresent());
        assertTrue(merged.get(key(2)).isPresent());
    }

    @Test
    void mergeIsSymmetric() {
        ChestMemory a = new ChestMemory();
        record(a, key(0), Material.DIAMOND, 1000L, 10);
        ChestMemory b = new ChestMemory();
        record(b, key(1), Material.GOLD_INGOT, 2000L, 10);

        ChestMemory mergedAb = ChestMemory.mergeFreshestWins(a, b, 10);
        ChestMemory mergedBa = ChestMemory.mergeFreshestWins(b, a, 10);

        assertEquals(mergedAb.asMap().keySet(), mergedBa.asMap().keySet());
    }
}

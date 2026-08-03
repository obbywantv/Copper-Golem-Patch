package dev.cgp.coppergolempatch.memory;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChestMemoryTest {

    private static final UUID WORLD = UUID.randomUUID();

    private static ChestKey key(int x) {
        return new ChestKey(WORLD, x, 64, 0);
    }

    private static void record(ChestMemory memory, ChestKey key, Material firstItemType, long now, int maxEntries) {
        memory.recordObservation(key, firstItemType, Set.of(firstItemType), now, maxEntries);
    }

    @Test
    void recordObservationUpsertsSameKeyInsteadOfDuplicating() {
        ChestMemory memory = new ChestMemory();
        record(memory, key(0), Material.DIAMOND, 1000L, 10);
        record(memory, key(0), Material.EMERALD, 2000L, 10);

        assertEquals(1, memory.size());
        assertEquals(Material.EMERALD, memory.get(key(0)).orElseThrow().firstItemType());
        assertEquals(2000L, memory.get(key(0)).orElseThrow().lastSeenEpochMillis());
    }

    @Test
    void evictsStalestEntryWhenOverCapacity() {
        ChestMemory memory = new ChestMemory();
        record(memory, key(0), Material.DIAMOND, 1000L, 2);
        record(memory, key(1), Material.EMERALD, 2000L, 2);
        record(memory, key(2), Material.GOLD_INGOT, 3000L, 2);

        assertEquals(2, memory.size());
        assertTrue(memory.get(key(0)).isEmpty(), "stalest entry should have been evicted");
        assertTrue(memory.get(key(1)).isPresent());
        assertTrue(memory.get(key(2)).isPresent());
    }

    @Test
    void getReturnsEmptyForUnknownKey() {
        ChestMemory memory = new ChestMemory();
        assertTrue(memory.get(key(99)).isEmpty());
    }

    @Test
    void memoryEntryIsStaleAfterTtlElapses() {
        MemoryEntry entry = new MemoryEntry(key(0), Material.DIAMOND, Set.of(Material.DIAMOND), 1_000L);
        assertFalse(entry.isStale(30, 1_000L + 29_000L));
        assertTrue(entry.isStale(30, 1_000L + 31_000L));
    }

    @Test
    void memoryEntryIsEmptyOnlyForAir() {
        assertTrue(new MemoryEntry(key(0), Material.AIR, Set.of(), 0L).isEmpty());
        assertFalse(new MemoryEntry(key(0), Material.DIAMOND, Set.of(Material.DIAMOND), 0L).isEmpty());
    }

    @Test
    void containsChecksAnySlotNotJustTheFirst() {
        MemoryEntry entry = new MemoryEntry(key(0), Material.OAK_LOG,
                Set.of(Material.OAK_LOG, Material.DIAMOND, Material.REDSTONE_BLOCK), 0L);
        assertTrue(entry.contains(Material.OAK_LOG));
        assertTrue(entry.contains(Material.DIAMOND), "a type present in any slot should count as a match");
        assertFalse(entry.contains(Material.EMERALD));
    }
}

package dev.cgp.coppergolempatch.memory;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnplaceableRegistryTest {

    @Test
    void markAndGetRoundTrips() {
        UnplaceableRegistry registry = new UnplaceableRegistry();
        registry.markUnplaceable(Material.DIAMOND, 1000L, 10);

        assertTrue(registry.get(Material.DIAMOND).isPresent());
        assertEquals(1000L, registry.get(Material.DIAMOND).orElseThrow().markedAtEpochMillis());
    }

    @Test
    void clearRemovesTheMark() {
        UnplaceableRegistry registry = new UnplaceableRegistry();
        registry.markUnplaceable(Material.DIAMOND, 1000L, 10);

        assertTrue(registry.clear(Material.DIAMOND));
        assertTrue(registry.get(Material.DIAMOND).isEmpty());
        assertFalse(registry.clear(Material.DIAMOND), "second clear of an already-cleared mark should report no-op");
    }

    @Test
    void entryIsStaleAfterTtlElapses() {
        UnplaceableEntry entry = new UnplaceableEntry(Material.DIAMOND, 1000L);
        assertFalse(entry.isStale(300, 1000L + 299_000L));
        assertTrue(entry.isStale(300, 1000L + 301_000L));
    }

    @Test
    void markExpiryIsTheRetryClockThatUnsticksAGolem() {
        // Regression: a golem that gave up on every type it knew stopped
        // picking anything up. Empty-handed, vanilla only offers it copper
        // chests, so it never ran a deposit search, never observed a newly
        // placed empty chest, and never reached the code clearing these
        // marks - "to clear the marks it must pick something up, to pick
        // something up the marks must be cleared". Expiry is the only exit
        // from that cycle, so the window has to stay on a human timescale:
        // at the former 300s default, golems ignored chests placed right
        // next to them for five minutes.
        UnplaceableEntry mark = new UnplaceableEntry(Material.DIAMOND, 0L);
        assertFalse(mark.isStale(20, 19_000L), "should still hold just before the retry window");
        assertTrue(mark.isStale(20, 21_000L), "should expire so the golem probes again");
    }

    @Test
    void mergeKeepsFreshestMarkOnConflictAndUnionsDisjointTypes() {
        UnplaceableRegistry a = new UnplaceableRegistry();
        a.markUnplaceable(Material.DIAMOND, 1000L, 10);
        a.markUnplaceable(Material.EMERALD, 2000L, 10);
        UnplaceableRegistry b = new UnplaceableRegistry();
        b.markUnplaceable(Material.DIAMOND, 5000L, 10);
        b.markUnplaceable(Material.GOLD_INGOT, 3000L, 10);

        UnplaceableRegistry merged = UnplaceableRegistry.mergeFreshestWins(a, b, 10);

        assertEquals(3, merged.size());
        assertEquals(5000L, merged.get(Material.DIAMOND).orElseThrow().markedAtEpochMillis());
        assertTrue(merged.get(Material.EMERALD).isPresent());
        assertTrue(merged.get(Material.GOLD_INGOT).isPresent());
    }

    @Test
    void mergeEvictsStalestWhenOverCapacity() {
        UnplaceableRegistry a = new UnplaceableRegistry();
        a.markUnplaceable(Material.DIAMOND, 1000L, 10);
        a.markUnplaceable(Material.EMERALD, 2000L, 10);
        UnplaceableRegistry b = new UnplaceableRegistry();
        b.markUnplaceable(Material.GOLD_INGOT, 3000L, 10);

        UnplaceableRegistry merged = UnplaceableRegistry.mergeFreshestWins(a, b, 2);

        assertEquals(2, merged.size());
        assertTrue(merged.get(Material.DIAMOND).isEmpty());
    }
}

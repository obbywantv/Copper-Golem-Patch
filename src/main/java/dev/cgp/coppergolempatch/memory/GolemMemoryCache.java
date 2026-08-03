package dev.cgp.coppergolempatch.memory;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory front for ChestMemoryCodec. TargetValidationListener, GossipTask
 * and CgpCommand read/mutate ChestMemory and UnplaceableRegistry through this
 * cache instead of decoding/encoding a golem's PersistentDataContainer on
 * every access. Entries are decoded lazily on first touch and kept resident;
 * dirty entries are flushed back to the PDC by the periodic GossipTask pass,
 * on plugin disable, and immediately when a golem is about to leave the
 * world (see {@link #onEntityRemove}) so a pending write is never lost to
 * the periodic flush's eviction.
 *
 * Not thread-safe: only ever touched from the server main thread, true of
 * every call site (event handlers and the Bukkit scheduler task).
 */
public final class GolemMemoryCache implements Listener {

    private final ChestMemoryCodec codec;

    private final Map<UUID, ChestMemory> memories = new HashMap<>();
    private final Map<UUID, UnplaceableRegistry> unplaceables = new HashMap<>();
    private final Set<UUID> dirtyMemory = new HashSet<>();
    private final Set<UUID> dirtyUnplaceable = new HashSet<>();

    public GolemMemoryCache(ChestMemoryCodec codec) {
        this.codec = codec;
    }

    public ChestMemory getMemory(CopperGolem golem) {
        return memories.computeIfAbsent(golem.getUniqueId(), id -> codec.read(golem.getPersistentDataContainer()));
    }

    public UnplaceableRegistry getUnplaceable(CopperGolem golem) {
        return unplaceables.computeIfAbsent(golem.getUniqueId(), id -> codec.readUnplaceable(golem.getPersistentDataContainer()));
    }

    /** Replaces a golem's cached chest memory (e.g. after a gossip merge) and marks it dirty. */
    public void putMemory(CopperGolem golem, ChestMemory memory) {
        memories.put(golem.getUniqueId(), memory);
        dirtyMemory.add(golem.getUniqueId());
    }

    /** Replaces a golem's cached unplaceable registry (e.g. after a gossip merge) and marks it dirty. */
    public void putUnplaceable(CopperGolem golem, UnplaceableRegistry registry) {
        unplaceables.put(golem.getUniqueId(), registry);
        dirtyUnplaceable.add(golem.getUniqueId());
    }

    /** Marks the already-resident memory dirty after an in-place mutation (e.g. recordObservation). */
    public void markMemoryDirty(CopperGolem golem) {
        dirtyMemory.add(golem.getUniqueId());
    }

    /** Marks the already-resident unplaceable registry dirty after an in-place mutation (e.g. clear/markUnplaceable). */
    public void markUnplaceableDirty(CopperGolem golem) {
        dirtyUnplaceable.add(golem.getUniqueId());
    }

    /**
     * Writes every dirty entry back to its golem's PersistentDataContainer,
     * then drops cache entries for golems no longer present in
     * currentlyLoaded. {@link #onEntityRemove} is what actually prevents
     * data loss when a golem disappears between flushes - this retainAll is
     * just a backstop against any removal path that event doesn't cover.
     */
    public void flush(Collection<CopperGolem> currentlyLoaded) {
        Map<UUID, CopperGolem> byId = new HashMap<>(currentlyLoaded.size());
        for (CopperGolem golem : currentlyLoaded) {
            byId.put(golem.getUniqueId(), golem);
        }

        for (UUID id : dirtyMemory) {
            CopperGolem golem = byId.get(id);
            if (golem != null && golem.isValid()) {
                codec.write(golem.getPersistentDataContainer(), memories.get(id));
            }
        }
        dirtyMemory.clear();

        for (UUID id : dirtyUnplaceable) {
            CopperGolem golem = byId.get(id);
            if (golem != null && golem.isValid()) {
                codec.writeUnplaceable(golem.getPersistentDataContainer(), unplaceables.get(id));
            }
        }
        dirtyUnplaceable.clear();

        memories.keySet().retainAll(byId.keySet());
        unplaceables.keySet().retainAll(byId.keySet());
    }

    private void flushAndEvict(CopperGolem golem) {
        UUID id = golem.getUniqueId();
        if (dirtyMemory.remove(id)) {
            codec.write(golem.getPersistentDataContainer(), memories.get(id));
        }
        if (dirtyUnplaceable.remove(id)) {
            codec.writeUnplaceable(golem.getPersistentDataContainer(), unplaceables.get(id));
        }
        memories.remove(id);
        unplaceables.remove(id);
    }

    /**
     * Fires for any entity leaving a world - death, chunk unload, etc. - while
     * it's still valid to read/write. Flushing here (rather than relying only
     * on the periodic sweep) is what guarantees a pending write made just
     * before a golem's chunk unloads isn't silently dropped.
     */
    @EventHandler
    public void onEntityRemove(EntityRemoveFromWorldEvent event) {
        if (event.getEntity() instanceof CopperGolem golem) {
            flushAndEvict(golem);
        }
    }
}

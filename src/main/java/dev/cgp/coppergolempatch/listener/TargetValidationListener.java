package dev.cgp.coppergolempatch.listener;

import dev.cgp.coppergolempatch.config.PluginConfig;
import dev.cgp.coppergolempatch.memory.ChestKey;
import dev.cgp.coppergolempatch.memory.ChestMemory;
import dev.cgp.coppergolempatch.memory.GolemMemoryCache;
import dev.cgp.coppergolempatch.memory.MemoryEntry;
import dev.cgp.coppergolempatch.memory.UnplaceableRegistry;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Learns chest contents from every candidate the vanilla search evaluates,
 * and uses that memory to skip chests already known to be wrong so the
 * golem doesn't waste a trip + 3s examine on them. A deposit chest matches
 * if it's empty OR contains the held item type ANYWHERE among its slots
 * (vanilla's own rule - not just whatever happens to occupy the first slot).
 * Also tracks, per golem, deposit candidates that rejected the item it's
 * currently carrying; once that search goes quiet (no new candidate for a
 * short while) the golem gives the item back to a copper chest and marks
 * that item type "unplaceable" (shared via gossip) so it and other golems
 * leave it alone until a spot opens up.
 */
public final class TargetValidationListener implements Listener {

    private static final long MIN_GIVE_UP_WINDOW_MILLIS = 1000L;
    private static final long GIVE_UP_QUIET_PERIOD_MILLIS = 1500L;

    private final Plugin plugin;
    private final Supplier<PluginConfig> config;
    private final GolemMemoryCache cache;
    private final Map<UUID, CarrySession> sessions = new HashMap<>();

    public TargetValidationListener(Plugin plugin, Supplier<PluginConfig> config, GolemMemoryCache cache) {
        this.plugin = plugin;
        this.config = config;
        this.cache = cache;
    }

    @EventHandler
    public void onValidate(ItemTransportingEntityValidateTargetEvent event) {
        if (!config.get().enabled()) {
            return;
        }
        if (!(event.getEntity() instanceof CopperGolem golem)) {
            return;
        }

        Block block = event.getBlock();
        long now = System.currentTimeMillis();

        BlockState state = block.getState();
        if (!(state instanceof Container container)) {
            return;
        }
        ItemStack[] contents = container.getInventory().getContents();

        if (isCopperChest(block.getType())) {
            // Vanilla only ever picks up whatever occupies the first
            // non-empty slot. If that happens to be an item we've already
            // learned has nowhere to go, every other (possibly perfectly
            // sortable) item behind it would be stuck forever - so bump the
            // blocked stack out of the way for the first sortable one we find.
            UnplaceableRegistry unplaceableForReorder = cache.getUnplaceable(golem);
            promoteSortableItem(container, contents, unplaceableForReorder, now);
        }

        ObservedContents observed = observeContents(contents);

        ChestKey key = ChestKey.of(block);
        ChestMemory memory = cache.getMemory(golem);
        memory.recordObservation(key, observed.firstItemType(), observed.allItemTypes(), now, config.get().maxEntriesPerGolem());
        cache.markMemoryDirty(golem);

        if (observed.firstItemType() == Material.AIR && !isCopperChest(block.getType())) {
            // A previously-unviable item could now fit here - give every
            // give-up mark a fresh chance instead of waiting out its TTL.
            UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
            if (unplaceable.clearAll()) {
                cache.markUnplaceableDirty(golem);
            }
        }

        decide(event, golem, key, memory, now);
    }

    private void decide(ItemTransportingEntityValidateTargetEvent event, CopperGolem golem,
                         ChestKey key, ChestMemory memory, long now) {
        Optional<MemoryEntry> entryOpt = memory.get(key);
        if (entryOpt.isEmpty() || entryOpt.get().isStale(config.get().entryTtlSeconds(), now)) {
            return;
        }
        MemoryEntry entry = entryOpt.get();

        ItemStack heldItem = golem.getEquipment().getItemInMainHand();
        Material heldType = heldItem.getType();
        boolean depositPhase = heldType != Material.AIR;

        CarrySession session = sessions.computeIfAbsent(golem.getUniqueId(), id -> new CarrySession());
        if (!Objects.equals(session.heldItem, depositPhase ? heldType : null)) {
            session.heldItem = depositPhase ? heldType : null;
            session.rejectedDeposits.clear();
            session.firstRejectionAtEpochMillis = 0L;
            session.lastNewRejectionAtEpochMillis = 0L;
            session.giveUpScheduled = false;
        }

        if (depositPhase) {
            if (isCopperChest(event.getBlock().getType())) {
                return;
            }
            boolean matches = entry.isEmpty() || entry.contains(heldType);
            event.setAllowed(matches);

            if (matches) {
                // A real way out exists - drop any rejection streak so a
                // handful of wrong neighbours checked moments earlier can't
                // still trigger a give-up after this.
                session.rejectedDeposits.clear();
                session.firstRejectionAtEpochMillis = 0L;
                session.lastNewRejectionAtEpochMillis = 0L;

                UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
                if (unplaceable.clear(heldType)) {
                    cache.markUnplaceableDirty(golem);
                }
            } else if (!session.giveUpScheduled) {
                if (session.rejectedDeposits.isEmpty()) {
                    session.firstRejectionAtEpochMillis = now;
                }
                if (session.rejectedDeposits.add(key)) {
                    // A genuinely new candidate, not a re-check of one we
                    // already rejected - the search is still actively
                    // turning up ground to cover.
                    session.lastNewRejectionAtEpochMillis = now;
                }
                // Give up once the search has gone quiet (no new candidate
                // proposed for a while) rather than after a fixed count: a
                // fixed number either gives up before a legitimately farther
                // chest is ever reached, or - if sized to this golem's whole
                // memory - may never trigger when memory holds chests that
                // simply aren't reachable from here. "Gone quiet" adapts to
                // however many chests vanilla actually proposes here.
                boolean minWindowPassed = now - session.firstRejectionAtEpochMillis >= MIN_GIVE_UP_WINDOW_MILLIS;
                boolean searchWentQuiet = now - session.lastNewRejectionAtEpochMillis >= GIVE_UP_QUIET_PERIOD_MILLIS;
                boolean hitSafetyCap = session.rejectedDeposits.size() >= config.get().giveUpAfterRejections();
                if (minWindowPassed && (searchWentQuiet || hitSafetyCap)) {
                    session.giveUpScheduled = true;
                    // Defer: we're inside the same call stack as the golem's
                    // own AI deciding what to do with this exact item, so
                    // mutating its equipment/a chest's inventory right now
                    // can desync vanilla's in-progress decision. Run it on
                    // the next tick instead, fully outside that call.
                    Bukkit.getScheduler().runTask(plugin, () -> giveUp(golem, heldType));
                }
            }
        } else {
            if (!isCopperChest(event.getBlock().getType())) {
                return;
            }
            if (entry.isEmpty()) {
                event.setAllowed(false);
                return;
            }
            UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
            event.setAllowed(!isUnplaceable(unplaceable, entry.firstItemType(), now));
        }
    }

    private void giveUp(CopperGolem golem, Material itemType) {
        if (!golem.isValid()) {
            return;
        }
        ItemStack currentlyHeld = golem.getEquipment().getItemInMainHand();
        if (currentlyHeld.getType() != itemType) {
            // Something else already resolved this (deposited, despawned, etc).
            return;
        }

        ChestMemory memory = cache.getMemory(golem);
        Optional<Container> target = findCopperChestWithRoom(golem, memory);
        if (target.isPresent()) {
            target.get().getInventory().addItem(currentlyHeld.clone());
        } else {
            golem.getWorld().dropItemNaturally(golem.getLocation(), currentlyHeld.clone());
        }
        golem.getEquipment().setItemInMainHand(new ItemStack(Material.AIR));

        CarrySession session = sessions.get(golem.getUniqueId());
        if (session != null) {
            session.heldItem = null;
            session.rejectedDeposits.clear();
            session.firstRejectionAtEpochMillis = 0L;
            session.lastNewRejectionAtEpochMillis = 0L;
            session.giveUpScheduled = false;
        }

        long now = System.currentTimeMillis();
        UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
        unplaceable.markUnplaceable(itemType, now, config.get().maxEntriesPerGolem());
        cache.markUnplaceableDirty(golem);
    }

    private Optional<Container> findCopperChestWithRoom(CopperGolem golem, ChestMemory memory) {
        UUID worldId = golem.getWorld().getUID();
        return memory.asMap().keySet().stream()
                .filter(key -> key.worldId().equals(worldId))
                .map(key -> golem.getWorld().getBlockAt(key.x(), key.y(), key.z()))
                .filter(block -> isCopperChest(block.getType()))
                .map(Block::getState)
                .filter(Container.class::isInstance)
                .map(Container.class::cast)
                .filter(container -> container.getInventory().firstEmpty() != -1)
                .min(Comparator.comparingDouble(c -> c.getLocation().distanceSquared(golem.getLocation())));
    }

    /** Swaps the first slot's stack with the first later stack of a different,
     * not-currently-unplaceable type, so vanilla's "grab the first item"
     * pickup naturally lands on something the golem can actually deliver.
     * Mutates contents in lockstep with the live inventory so callers don't
     * need to re-fetch it to see the post-swap state. */
    private void promoteSortableItem(Container container, ItemStack[] contents, UnplaceableRegistry unplaceable, long now) {
        int firstIndex = -1;
        for (int i = 0; i < contents.length; i++) {
            if (contents[i] != null && contents[i].getType() != Material.AIR) {
                firstIndex = i;
                break;
            }
        }
        if (firstIndex == -1) {
            return;
        }
        Material firstType = contents[firstIndex].getType();
        if (!isUnplaceable(unplaceable, firstType, now)) {
            return;
        }

        for (int i = firstIndex + 1; i < contents.length; i++) {
            ItemStack candidate = contents[i];
            if (candidate == null || candidate.getType() == Material.AIR || candidate.getType() == firstType) {
                continue;
            }
            if (isUnplaceable(unplaceable, candidate.getType(), now)) {
                continue;
            }
            ItemStack blocked = contents[firstIndex];
            container.getInventory().setItem(firstIndex, candidate);
            container.getInventory().setItem(i, blocked);
            contents[firstIndex] = candidate;
            contents[i] = blocked;
            return;
        }
    }

    private boolean isUnplaceable(UnplaceableRegistry unplaceable, Material type, long now) {
        return unplaceable.get(type)
                .filter(mark -> !mark.isStale(config.get().unplaceableTtlSeconds(), now))
                .isPresent();
    }

    private boolean isCopperChest(Material material) {
        return material.name().endsWith("COPPER_CHEST");
    }

    private ObservedContents observeContents(ItemStack[] contents) {
        Material first = Material.AIR;
        Set<Material> all = new LinkedHashSet<>();
        for (ItemStack item : contents) {
            if (item != null && item.getType() != Material.AIR) {
                if (first == Material.AIR) {
                    first = item.getType();
                }
                all.add(item.getType());
            }
        }
        return new ObservedContents(first, all);
    }

    private record ObservedContents(Material firstItemType, Set<Material> allItemTypes) {
    }

    private static final class CarrySession {
        private Material heldItem;
        private final Set<ChestKey> rejectedDeposits = new HashSet<>();
        private long firstRejectionAtEpochMillis;
        private long lastNewRejectionAtEpochMillis;
        private boolean giveUpScheduled;
    }
}

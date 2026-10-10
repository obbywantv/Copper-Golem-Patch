package dev.cgp.coppergolempatch.listener;

import dev.cgp.coppergolempatch.config.PluginConfig;
import dev.cgp.coppergolempatch.memory.ChestKey;
import dev.cgp.coppergolempatch.memory.ChestMemory;
import dev.cgp.coppergolempatch.memory.GolemMemoryCache;
import dev.cgp.coppergolempatch.memory.MemoryEntry;
import dev.cgp.coppergolempatch.memory.UnplaceableRegistry;
import dev.cgp.coppergolempatch.util.ItemFrameLabels;
import io.papermc.paper.event.entity.ItemTransportingEntityValidateTargetEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.CopperGolem;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
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

        Material heldType = heldItemType(golem);
        CarrySession session = sessions.computeIfAbsent(golem.getUniqueId(), id -> new CarrySession());
        if (heldType != Material.AIR) {
            if (!Objects.equals(session.heldItem, heldType)) {
                session.heldItem = heldType;
                session.rejectedDeposits.clear();
                session.firstRejectionAtEpochMillis = 0L;
                session.lastNewRejectionAtEpochMillis = 0L;
                session.giveUpScheduled = false;
            }
        } else if (session.heldItem != null) {
            session.heldItem = null;
            session.rejectedDeposits.clear();
            session.firstRejectionAtEpochMillis = 0L;
            session.lastNewRejectionAtEpochMillis = 0L;
            session.giveUpScheduled = false;
        }

        Set<Material> labels = Set.of();
        if (config.get().itemFrameLabelsEnabled() && isVanillaDepositChest(block.getType())) {
            labels = ItemFrameLabels.labelsOn(block);
        }

        boolean deniedByLabel = false;
        if (!labels.isEmpty() && heldType != Material.AIR && !labels.contains(heldType)) {
            event.setAllowed(false);
            deniedByLabel = true;
        }

        ItemStack[] contents = container.getInventory().getContents();

        if (isCopperChest(block.getType())) {
            UnplaceableRegistry unplaceableForReorder = cache.getUnplaceable(golem);
            promoteSortableItem(container, contents, unplaceableForReorder, now);
            contents = container.getInventory().getContents();
        }

        ObservedContents observed = observeContents(contents);

        ChestKey key = ChestKey.of(block);
        ChestMemory memory = cache.getMemory(golem);
        if (!labels.isEmpty()) {
            Material labelFirst = labels.iterator().next();
            memory.recordObservation(key, labelFirst, labels, now, config.get().maxEntriesPerGolem());
        } else {
            memory.recordObservation(key, observed.firstItemType(), observed.allItemTypes(), now, config.get().maxEntriesPerGolem());
        }
        cache.markMemoryDirty(golem);

        if (observed.firstItemType() == Material.AIR && !isCopperChest(block.getType()) && labels.isEmpty()) {
            UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
            if (unplaceable.clearAll()) {
                cache.markUnplaceableDirty(golem);
            }
        }

        decide(event, golem, key, memory, now, heldType, deniedByLabel, labels);
        if (deniedByLabel) {
            event.setAllowed(false);
        }
    }

    private void decide(ItemTransportingEntityValidateTargetEvent event, CopperGolem golem,
                         ChestKey key, ChestMemory memory, long now, Material heldType,
                         boolean deniedByLabel, Set<Material> labels) {
        boolean depositPhase = heldType != Material.AIR;
        CarrySession session = sessions.computeIfAbsent(golem.getUniqueId(), id -> new CarrySession());

        if (depositPhase && !labels.isEmpty()) {
            boolean allowed = !deniedByLabel && labels.contains(heldType);
            if (allowed) {
                ObservedContents live = observeContents(
                        event.getBlock().getState() instanceof Container c
                                ? c.getInventory().getContents()
                                : new ItemStack[0]);
                allowed = live.firstItemType() == Material.AIR || live.allItemTypes().contains(heldType);
            }
            event.setAllowed(allowed);
            if (allowed) {
                session.rejectedDeposits.clear();
                session.firstRejectionAtEpochMillis = 0L;
                session.lastNewRejectionAtEpochMillis = 0L;
                UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
                if (unplaceable.clear(heldType)) {
                    cache.markUnplaceableDirty(golem);
                }
            } else {
                trackRejection(golem, key, heldType, now);
            }
            return;
        }

        Optional<MemoryEntry> entryOpt = memory.get(key);
        if (entryOpt.isEmpty() || entryOpt.get().isStale(config.get().entryTtlSeconds(), now)) {
            if (deniedByLabel) {
                trackRejection(golem, key, heldType, now);
            }
            return;
        }
        MemoryEntry entry = entryOpt.get();

        if (depositPhase) {
            if (isCopperChest(event.getBlock().getType())) {
                return;
            }
            boolean matches = !deniedByLabel && matchesDepositTarget(event.getBlock(), entry, heldType);
            event.setAllowed(matches);

            if (matches) {
                session.rejectedDeposits.clear();
                session.firstRejectionAtEpochMillis = 0L;
                session.lastNewRejectionAtEpochMillis = 0L;

                UnplaceableRegistry unplaceable = cache.getUnplaceable(golem);
                if (unplaceable.clear(heldType)) {
                    cache.markUnplaceableDirty(golem);
                }
            } else {
                trackRejection(golem, key, heldType, now);
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

    private void trackRejection(CopperGolem golem, ChestKey key, Material heldType, long now) {
        CarrySession session = sessions.computeIfAbsent(golem.getUniqueId(), id -> new CarrySession());
        if (session.giveUpScheduled || heldType == Material.AIR) {
            return;
        }
        if (session.rejectedDeposits.isEmpty()) {
            session.firstRejectionAtEpochMillis = now;
        }
        if (session.rejectedDeposits.add(key)) {
            session.lastNewRejectionAtEpochMillis = now;
        }
        boolean minWindowPassed = now - session.firstRejectionAtEpochMillis >= MIN_GIVE_UP_WINDOW_MILLIS;
        boolean searchWentQuiet = now - session.lastNewRejectionAtEpochMillis >= GIVE_UP_QUIET_PERIOD_MILLIS;
        boolean hitSafetyCap = session.rejectedDeposits.size() >= config.get().giveUpAfterRejections();
        if (minWindowPassed && (searchWentQuiet || hitSafetyCap)) {
            session.giveUpScheduled = true;
            Bukkit.getScheduler().runTask(plugin, () -> giveUp(golem, heldType));
        }
    }

    private Material heldItemType(CopperGolem golem) {
        ItemStack main = golem.getEquipment().getItemInMainHand();
        if (main != null && main.getType() != Material.AIR) {
            return main.getType();
        }
        return Material.AIR;
    }

    private boolean matchesDepositTarget(Block block, MemoryEntry entry, Material heldType) {
        boolean vanillaMatch = entry.isEmpty() || entry.contains(heldType);
        if (!config.get().itemFrameLabelsEnabled()) {
            return vanillaMatch;
        }
        Set<Material> labels = ItemFrameLabels.labelsOn(block);
        if (labels.isEmpty()) {
            return vanillaMatch;
        }
        return labels.contains(heldType) && vanillaMatch;
    }

    private boolean isVanillaDepositChest(Material material) {
        return material == Material.CHEST || material == Material.TRAPPED_CHEST;
    }

    private void giveUp(CopperGolem golem, Material itemType) {
        if (!golem.isValid()) {
            return;
        }
        ItemStack currentlyHeld = golem.getEquipment().getItemInMainHand();
        if (currentlyHeld.getType() != itemType) {
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

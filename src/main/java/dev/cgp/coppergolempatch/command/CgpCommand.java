package dev.cgp.coppergolempatch.command;

import dev.cgp.coppergolempatch.CopperGolemPatchPlugin;
import dev.cgp.coppergolempatch.memory.ChestMemory;
import dev.cgp.coppergolempatch.memory.GolemMemoryCache;
import dev.cgp.coppergolempatch.memory.MemoryEntry;
import dev.cgp.coppergolempatch.util.ItemFrameLabels;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class CgpCommand implements BasicCommand {

    private final CopperGolemPatchPlugin plugin;
    private final GolemMemoryCache cache;

    public CgpCommand(CopperGolemPatchPlugin plugin, GolemMemoryCache cache) {
        this.plugin = plugin;
        this.cache = cache;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        var sender = stack.getSender();
        if (args.length == 0) {
            sender.sendMessage("Usage: /cgp <reload|info|labels|toggle>");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                boolean replaced = plugin.reloadPluginConfig();
                if (replaced) {
                    sender.sendMessage("[Copper Golem Patch] Outdated config.yml was backed up and replaced, then reloaded.");
                } else {
                    sender.sendMessage("[Copper Golem Patch] Configuration reloaded.");
                }
            }
            case "info" -> handleInfo(stack);
            case "labels" -> handleLabels(stack);
            case "toggle" -> handleToggle(stack);
            default -> sender.sendMessage("Usage: /cgp <reload|info|labels|toggle>");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "info", "labels", "toggle");
        }
        return List.of();
    }

    @Override
    public String permission() {
        return "coppergolempatch.admin";
    }

    private void handleInfo(CommandSourceStack stack) {
        var sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Copper Golem Patch] This command must be run by a player.");
            return;
        }
        Optional<CopperGolem> nearest = findNearestGolem(player);
        if (nearest.isEmpty()) {
            sender.sendMessage("[Copper Golem Patch] No copper golem within 10 blocks.");
            return;
        }
        ChestMemory memory = cache.getMemory(nearest.get());
        Collection<MemoryEntry> entries = memory.asMap().values();
        if (entries.isEmpty()) {
            sender.sendMessage("[Copper Golem Patch] That golem has no chest memory yet.");
            return;
        }
        long oldest = entries.stream().mapToLong(MemoryEntry::lastSeenEpochMillis).min().orElseThrow();
        long newest = entries.stream().mapToLong(MemoryEntry::lastSeenEpochMillis).max().orElseThrow();
        int unplaceableCount = cache.getUnplaceable(nearest.get()).size();
        sender.sendMessage(String.format(
                "[Copper Golem Patch] %d known chest(s) - oldest seen %s, newest seen %s - %d unplaceable item type(s)",
                entries.size(), Instant.ofEpochMilli(oldest), Instant.ofEpochMilli(newest), unplaceableCount));
    }

    private void handleLabels(CommandSourceStack stack) {
        var sender = stack.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage("[Copper Golem Patch] This command must be run by a player.");
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null || (target.getType() != Material.CHEST && target.getType() != Material.TRAPPED_CHEST)) {
            sender.sendMessage("[Copper Golem Patch] Look at a chest within 6 blocks.");
            return;
        }
        Set<Material> labels = ItemFrameLabels.labelsOn(target);
        if (labels.isEmpty()) {
            sender.sendMessage("[Copper Golem Patch] No item-frame label detected on that chest.");
            return;
        }
        String joined = labels.stream().map(Enum::name).collect(Collectors.joining(", "));
        sender.sendMessage("[Copper Golem Patch] Labels: " + joined);
    }

    private void handleToggle(CommandSourceStack stack) {
        boolean newValue = !plugin.getPluginConfig().enabled();
        plugin.getConfig().set("enabled", newValue);
        plugin.saveConfig();
        plugin.reloadPluginConfig();
        stack.getSender().sendMessage("[Copper Golem Patch] enabled = " + newValue);
    }

    private Optional<CopperGolem> findNearestGolem(Player player) {
        return player.getNearbyEntities(10, 10, 10).stream()
                .filter(CopperGolem.class::isInstance)
                .map(CopperGolem.class::cast)
                .min(Comparator.comparingDouble(g -> g.getLocation().distanceSquared(player.getLocation())));
    }
}

package dev.cgp.coppergolempatch.command;

import dev.cgp.coppergolempatch.CopperGolemPatchPlugin;
import dev.cgp.coppergolempatch.memory.ChestMemory;
import dev.cgp.coppergolempatch.memory.GolemMemoryCache;
import dev.cgp.coppergolempatch.memory.MemoryEntry;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.CopperGolem;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

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
            sender.sendMessage("Usage: /cgp <reload|info|toggle>");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                plugin.reloadPluginConfig();
                sender.sendMessage("[Copper Golem Patch] Configuration reloaded.");
            }
            case "info" -> handleInfo(stack);
            case "toggle" -> handleToggle(stack);
            default -> sender.sendMessage("Usage: /cgp <reload|info|toggle>");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "info", "toggle");
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

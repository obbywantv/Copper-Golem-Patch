package dev.cgp.coppergolempatch.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public record PluginConfig(
        boolean enabled,
        boolean gossipEnabled,
        double gossipRadius,
        long gossipIntervalTicks,
        int maxEntriesPerGolem,
        long entryTtlSeconds,
        int giveUpAfterRejections,
        long unplaceableTtlSeconds
) {

    public static PluginConfig load(JavaPlugin plugin) {
        plugin.reloadConfig();
        FileConfiguration config = plugin.getConfig();

        double gossipRadius = config.getDouble("gossip.radius", 12.0);
        if (gossipRadius <= 0) {
            plugin.getLogger().warning("gossip.radius must be > 0, using default 12.0");
            gossipRadius = 12.0;
        }

        long gossipIntervalTicks = config.getLong("gossip.interval-ticks", 30);
        if (gossipIntervalTicks <= 0) {
            plugin.getLogger().warning("gossip.interval-ticks must be > 0, using default 30");
            gossipIntervalTicks = 30;
        }

        int maxEntriesPerGolem = config.getInt("memory.max-entries-per-golem", 32);
        if (maxEntriesPerGolem <= 0) {
            plugin.getLogger().warning("memory.max-entries-per-golem must be > 0, using default 32");
            maxEntriesPerGolem = 32;
        }

        long entryTtlSeconds = config.getLong("memory.entry-ttl-seconds", 1800);
        if (entryTtlSeconds <= 0) {
            plugin.getLogger().warning("memory.entry-ttl-seconds must be > 0, using default 1800");
            entryTtlSeconds = 1800;
        }

        int giveUpAfterRejections = config.getInt("give-up.after-rejections", 50);
        if (giveUpAfterRejections <= 0) {
            plugin.getLogger().warning("give-up.after-rejections must be > 0, using default 50");
            giveUpAfterRejections = 50;
        }

        long unplaceableTtlSeconds = config.getLong("give-up.unplaceable-ttl-seconds", 300);
        if (unplaceableTtlSeconds <= 0) {
            plugin.getLogger().warning("give-up.unplaceable-ttl-seconds must be > 0, using default 300");
            unplaceableTtlSeconds = 300;
        }

        return new PluginConfig(
                config.getBoolean("enabled", true),
                config.getBoolean("gossip.enabled", true),
                gossipRadius,
                gossipIntervalTicks,
                maxEntriesPerGolem,
                entryTtlSeconds,
                giveUpAfterRejections,
                unplaceableTtlSeconds
        );
    }
}

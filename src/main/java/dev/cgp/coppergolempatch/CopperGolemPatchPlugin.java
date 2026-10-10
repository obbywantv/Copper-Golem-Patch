package dev.cgp.coppergolempatch;

import dev.cgp.coppergolempatch.command.CgpCommand;
import dev.cgp.coppergolempatch.config.ConfigInstaller;
import dev.cgp.coppergolempatch.config.PluginConfig;
import dev.cgp.coppergolempatch.gossip.GossipTask;
import dev.cgp.coppergolempatch.listener.TargetValidationListener;
import dev.cgp.coppergolempatch.memory.ChestMemoryCodec;
import dev.cgp.coppergolempatch.memory.GolemMemoryCache;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.World;
import org.bukkit.entity.CopperGolem;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

public final class CopperGolemPatchPlugin extends JavaPlugin {

    private PluginConfig config;
    private GolemMemoryCache cache;
    private BukkitTask gossipTask;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        ConfigInstaller.ensureUpToDate(this);
        config = PluginConfig.load(this);
        ChestMemoryCodec codec = new ChestMemoryCodec(this, getLogger());
        cache = new GolemMemoryCache(codec);
        getServer().getPluginManager().registerEvents(cache, this);

        getServer().getPluginManager().registerEvents(
                new TargetValidationListener(this, () -> config, cache), this);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register("cgp", "Copper Golem Patch admin commands", new CgpCommand(this, cache));
        });

        if (config.enabled()) {
            scheduleGossipTask();
        }

        getLogger().info("Copper Golem Patch enabled.");
    }

    @Override
    public void onDisable() {
        if (gossipTask != null) {
            gossipTask.cancel();
        }
        if (cache != null) {
            List<CopperGolem> loaded = new ArrayList<>();
            for (World world : getServer().getWorlds()) {
                loaded.addAll(world.getEntitiesByClass(CopperGolem.class));
            }
            cache.flush(loaded);
        }
        getLogger().info("Copper Golem Patch disabled.");
    }

    public PluginConfig getPluginConfig() {
        return config;
    }

    /**
     * @return {@code true} if {@code config.yml} was replaced because it was outdated
     */
    public boolean reloadPluginConfig() {
        boolean replaced = ConfigInstaller.ensureUpToDate(this);
        config = PluginConfig.load(this);
        if (gossipTask != null) {
            gossipTask.cancel();
            gossipTask = null;
        }
        if (config.enabled()) {
            scheduleGossipTask();
        }
        return replaced;
    }

    private void scheduleGossipTask() {
        GossipTask task = new GossipTask(() -> config, cache);
        gossipTask = getServer().getScheduler().runTaskTimer(this, task, 0L, config.gossipIntervalTicks());
    }
}

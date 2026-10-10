package dev.cgp.coppergolempatch.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;

/**
 * Keeps {@code config.yml} on disk aligned with the jar copy via
 * {@code config_version}. Outdated or missing versions are backed up and
 * replaced; callers then reload from disk.
 */
public final class ConfigInstaller {

    /** Bump when the shipped config.yml schema/comments change meaningfully. */
    public static final int CURRENT_VERSION = 1;

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ConfigInstaller() {
    }

    /**
     * @return {@code true} if the on-disk file was replaced with the jar default
     */
    public static boolean ensureUpToDate(JavaPlugin plugin) {
        Path dataFolder = plugin.getDataFolder().toPath();
        Path configPath = dataFolder.resolve("config.yml");

        int expected = readShippedVersion(plugin);
        if (!Files.isRegularFile(configPath)) {
            plugin.saveDefaultConfig();
            plugin.getLogger().info("Created config.yml (config_version " + expected + ").");
            return false;
        }

        int installed = readInstalledVersion(configPath);
        if (installed >= expected) {
            return false;
        }

        Path backup = dataFolder.resolve(
                "config.yml.bak-v" + installed + "-" + LocalDateTime.now().format(BACKUP_STAMP));
        try {
            Files.move(configPath, backup);
        } catch (IOException moveFailed) {
            try {
                Files.copy(configPath, backup);
                Files.deleteIfExists(configPath);
            } catch (IOException copyFailed) {
                plugin.getLogger().log(Level.SEVERE,
                        "Could not backup outdated config.yml; leaving the old file in place.",
                        copyFailed);
                return false;
            }
        }

        plugin.saveResource("config.yml", true);
        plugin.getLogger().warning(
                "config.yml was outdated (config_version " + installed
                        + ", expected " + expected + "). "
                        + "Backed up to " + backup.getFileName()
                        + " and installed a fresh config from the jar. "
                        + "Re-apply any custom values from the backup, then /cgp reload.");
        return true;
    }

    private static int readInstalledVersion(Path configPath) {
        FileConfiguration yaml = YamlConfiguration.loadConfiguration(configPath.toFile());
        if (!yaml.contains("config_version")) {
            return 0;
        }
        return yaml.getInt("config_version", 0);
    }

    private static int readShippedVersion(JavaPlugin plugin) {
        try (InputStream in = plugin.getResource("config.yml")) {
            if (in == null) {
                return CURRENT_VERSION;
            }
            FileConfiguration shipped = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            int version = shipped.getInt("config_version", CURRENT_VERSION);
            return version > 0 ? version : CURRENT_VERSION;
        } catch (IOException ignored) {
            return CURRENT_VERSION;
        }
    }
}

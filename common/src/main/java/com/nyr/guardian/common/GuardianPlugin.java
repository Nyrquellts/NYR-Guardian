package com.nyr.guardian.common;

import com.tcoded.folialib.FoliaLib;
import com.tcoded.folialib.impl.PlatformScheduler;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * The shared body of every NYR Guardian plugin: config.yml that keeps a buyer's edits, messages, staff alerts, one command with
 * status/reload/help, scheduling that works on Folia, Paper and Spigot alike, and a clean stop and reload.
 */
public abstract class GuardianPlugin extends JavaPlugin {

    private final List<Listener> listeners = new ArrayList<>();
    private Platform platform;
    private FoliaLib foliaLib;
    private YamlConfiguration settings;
    private Messages messages;
    private Alerts alerts;
    private WorldFilter worlds = WorldFilter.everywhere();
    private boolean running;
    private String configProblem;

    /** Lower-case module id, used in log and thread names. Permissions and bundled files follow the plugin name instead. */
    public abstract String pluginId();

    /** The name buyers see, e.g. "NYR CombatTag Pro". */
    public abstract String displayName();

    /** The command declared in plugin.yml. */
    protected abstract String commandName();

    /** Registers listeners and tasks through {@link #listen} and {@link #scheduler}; runs at start and after every reload. */
    protected abstract void startPlugin();

    /** Undoes whatever startPlugin changed in the world; listeners and tasks are removed by the caller. */
    protected abstract void stopPlugin();

    /** Adds this plugin's own lines to /&lt;command&gt; status. */
    protected abstract void status(List<String> lines);

    /** Adds this plugin's own subcommands. */
    protected void commands(NyrCommand command) {
    }

    /** Config sections whose entries belong to the buyer; see {@link ConfigFile#load(File, InputStream, java.util.logging.Logger, Set)}. */
    protected Set<String> freeFormSections() {
        return Set.of();
    }

    @Override
    public final void onEnable() {
        platform = Platform.detect(getServer().getVersion(), getServer().getBukkitVersion());
        foliaLib = new FoliaLib(this);
        messages = new Messages(null);
        alerts = new Alerts(getName() + "-alerts", permission("alerts"), getLogger(),
            new File(getDataFolder(), "alerts.log"), () -> getServer().getOnlinePlayers());
        loadSettings();

        NyrCommand command = new NyrCommand(messages)
            .add("status", "", permission("admin"), "What this plugin is doing right now", (sender, args) -> sendStatus(sender), null)
            .add("reload", "", permission("admin"), "Reload config.yml", (sender, args) -> reload(sender), null);
        commands(command);
        PluginCommand declared = getCommand(commandName());
        if (declared != null) {
            declared.setExecutor(command);
            declared.setTabCompleter(command);
        }

        start();
        getLogger().info(displayName() + " " + version() + " on " + platform.describe() + (running ? "" : " (switched off in config.yml)"));
    }

    @Override
    public final void onDisable() {
        stop();
        if (foliaLib != null) {
            foliaLib.getScheduler().cancelAllTasks();
        }
        if (alerts != null) {
            alerts.close();
        }
    }

    private void start() {
        running = settings.getBoolean("enabled", true);
        if (running) {
            startPlugin();
        }
    }

    private void stop() {
        if (running) {
            try {
                stopPlugin();
            } finally {
                running = false;
                for (Listener listener : listeners) {
                    HandlerList.unregisterAll(listener);
                }
                listeners.clear();
                if (foliaLib != null) {
                    foliaLib.getScheduler().cancelAllTasks();
                }
            }
        }
    }

    private void loadSettings() {
        InputStream bundled = getResource(bundledPath("config.yml"));
        if (bundled == null) {
            throw new IllegalStateException("the jar has no " + bundledPath("config.yml"));
        }
        ConfigFile.Loaded loaded = ConfigFile.load(new File(getDataFolder(), "config.yml"), bundled, getLogger(), freeFormSections());
        settings = loaded.config();
        configProblem = loaded.problem();
        messages.use(settings.getConfigurationSection("messages"));
        alerts.configure(settings.getConfigurationSection("alerts"));
        worlds = WorldFilter.from(settings.getConfigurationSection("worlds"));
    }

    /** Stops the plugin, reads config.yml again and starts it with the new settings. */
    public final void reload(CommandSender sender) {
        stop();
        loadSettings();
        start();
        if (configProblem != null) {
            messages.send(sender, "reload-failed", "problem", configProblem);
        } else {
            messages.send(sender, "reloaded", "state", running ? "on" : "off");
        }
    }

    private void sendStatus(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        if (running) {
            status(lines);
        }
        messages.send(sender, "status-header", "name", displayName(), "version", version(), "platform", platform.describe(),
            "state", running ? messages.format("state-on") : messages.format("state-off"));
        for (String line : lines) {
            sender.sendMessage(Text.color(line));
        }
    }

    /** The plugin name in lower case without dashes, then the node: NYR-CombatTagPro gives nyrcombattagpro.&lt;node&gt;. */
    public final String permission(String node) {
        return getName().toLowerCase(java.util.Locale.ROOT).replace("-", "") + "." + node;
    }

    protected final String bundledPath(String file) {
        return getName().toLowerCase(java.util.Locale.ROOT) + "/" + file;
    }

    public final void listen(Listener listener) {
        getServer().getPluginManager().registerEvents(listener, this);
        listeners.add(listener);
    }

    public final PlatformScheduler scheduler() {
        return foliaLib.getScheduler();
    }

    public final Platform platform() {
        return platform;
    }

    public final YamlConfiguration settings() {
        return settings;
    }

    public final Messages messages() {
        return messages;
    }

    public final Alerts alerts() {
        return alerts;
    }

    public final WorldFilter worlds() {
        return worlds;
    }

    public final boolean running() {
        return running;
    }

    @SuppressWarnings("deprecation")
    public final String version() {
        return getDescription().getVersion();
    }

    /** The plugin's own settings live in {@link #settings()}; Bukkit's config.yml handling is not used. */
    @Override
    public final FileConfiguration getConfig() {
        return settings;
    }

    @Override
    public final void saveDefaultConfig() {
    }

    @Override
    public final void reloadConfig() {
        if (messages != null && alerts != null) {
            loadSettings();
        }
    }

    @Override
    public final void saveConfig() {
    }
}

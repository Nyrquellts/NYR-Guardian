package com.nyr.guardian.dupesentry;

import com.nyr.guardian.common.Platform;
import java.io.File;
import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.Map;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Which of DupeSentry's dupes the server already blocks by itself, read once at start.
 *
 * <p>Paper, Purpur and Folia block the piston, end portal and tripwire hook dupes unless an admin turns on the matching
 * {@code unsupported-settings} in config/paper-global.yml. A guard must not run on top of such a fix: Paper's piston fix
 * moves a pushed block's current state, so the carpet that broke off is the only copy left, and cancelling its drop would
 * delete it. Spigot and CraftBukkit have none of these fixes.
 */
public final class ServerFixes {

    /** What the server does about one dupe. */
    public enum Verdict {
        /** The server lets the dupe happen: the guard runs. */
        ALLOWS,
        /** The server blocks the dupe itself: the guard stays idle. */
        BLOCKS,
        /** A Paper server whose setting could not be read: the guard stays idle rather than risk deleting items. */
        UNKNOWN
    }

    private static final String GLOBAL_CONFIGURATION = "io.papermc.paper.configuration.GlobalConfiguration";

    private final String server;
    private final Map<Guard, Verdict> verdicts;

    ServerFixes(String server, Map<Guard, Verdict> verdicts) {
        this.server = server;
        this.verdicts = new EnumMap<>(Guard.class);
        this.verdicts.putAll(verdicts);
    }

    /** A server that blocks none of the dupes, as Spigot. */
    static ServerFixes none(String server) {
        return new ServerFixes(server, Map.of());
    }

    /**
     * Reads Paper's live global configuration, falling back to {@code paperGlobal} (config/paper-global.yml) when the class
     * cannot be read. A setting a Paper version does not have (skip-tripwire-hook-placement-validation before 1.21) means
     * that version has no such fix.
     */
    static ServerFixes detect(Platform platform, File paperGlobal) {
        String server = platform.software().display();
        if (!platform.paperBased()) {
            return none(server);
        }
        Map<Guard, Verdict> verdicts = fromRunningServer();
        if (verdicts == null) {
            verdicts = fromFile(paperGlobal);
        }
        return new ServerFixes(server, verdicts);
    }

    private static Map<Guard, Verdict> fromRunningServer() {
        Object unsupported;
        try {
            Class<?> type = Class.forName(GLOBAL_CONFIGURATION);
            Object config = type.getMethod("get").invoke(null);
            if (config == null) {
                return null;
            }
            unsupported = type.getField("unsupportedSettings").get(config);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException notReadable) {
            return null;
        }
        if (unsupported == null) {
            return null;
        }
        Map<Guard, Verdict> verdicts = new EnumMap<>(Guard.class);
        for (Guard guard : Guard.values()) {
            if (guard.paperField() == null) {
                continue;
            }
            try {
                Field field = unsupported.getClass().getField(guard.paperField());
                verdicts.put(guard, field.getBoolean(unsupported) ? Verdict.ALLOWS : Verdict.BLOCKS);
            } catch (NoSuchFieldException absent) {
                verdicts.put(guard, Verdict.ALLOWS);
            } catch (ReflectiveOperationException | RuntimeException notReadable) {
                verdicts.put(guard, Verdict.UNKNOWN);
            }
        }
        return verdicts;
    }

    static Map<Guard, Verdict> fromFile(File paperGlobal) {
        Map<Guard, Verdict> verdicts = new EnumMap<>(Guard.class);
        YamlConfiguration yaml = paperGlobal != null && paperGlobal.isFile() ? YamlConfiguration.loadConfiguration(paperGlobal) : null;
        boolean readable = yaml != null && yaml.isConfigurationSection("unsupported-settings");
        for (Guard guard : Guard.values()) {
            if (guard.paperSetting() == null) {
                continue;
            }
            String path = "unsupported-settings." + guard.paperSetting();
            if (!readable) {
                verdicts.put(guard, Verdict.UNKNOWN);
            } else if (yaml.isBoolean(path)) {
                verdicts.put(guard, yaml.getBoolean(path) ? Verdict.ALLOWS : Verdict.BLOCKS);
            } else {
                verdicts.put(guard, guard == Guard.TRIPWIRE_HOOKS ? Verdict.ALLOWS : Verdict.UNKNOWN);
            }
        }
        return verdicts;
    }

    public Verdict verdict(Guard guard) {
        return verdicts.getOrDefault(guard, Verdict.ALLOWS);
    }

    /** True when the guard has something to stop on this server. */
    public boolean needed(Guard guard) {
        return verdict(guard) == Verdict.ALLOWS;
    }

    /** "Paper", "Purpur", "Folia", "Spigot" or "CraftBukkit". */
    public String server() {
        return server;
    }
}

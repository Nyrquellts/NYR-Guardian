package com.nyr.guardian.common;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * A plugin's config.yml. A buyer's edits are never overwritten: settings added by an update are written into the existing
 * file with their comments, and a file that does not parse is left alone while the plugin runs on its defaults.
 */
public final class ConfigFile {

    /** What loading found, for the start-up log. */
    public record Loaded(YamlConfiguration config, List<String> added, String problem) {
    }

    private ConfigFile() {
    }

    public static Loaded load(File file, InputStream bundledDefaults, Logger logger) {
        return load(file, bundledDefaults, logger, java.util.Set.of());
    }

    /**
     * {@code freeForm} names sections whose entries belong to the buyer, such as a map of permission caps: an entry deleted
     * there stays deleted, and the defaults' entries are only written when the whole section is missing.
     */
    public static Loaded load(File file, InputStream bundledDefaults, Logger logger, java.util.Set<String> freeForm) {
        byte[] bundled;
        try (InputStream in = bundledDefaults) {
            bundled = in.readAllBytes();
        } catch (IOException unreadable) {
            throw new IllegalStateException("the bundled default config cannot be read", unreadable);
        }
        YamlConfiguration defaults = new YamlConfiguration();
        try (Reader reader = new InputStreamReader(new java.io.ByteArrayInputStream(bundled), StandardCharsets.UTF_8)) {
            defaults.load(reader);
        } catch (IOException | InvalidConfigurationException broken) {
            throw new IllegalStateException("the bundled default config does not parse", broken);
        }

        if (!file.exists()) {
            // The bundled file as written, blank lines and all.
            try {
                Files.createDirectories(file.toPath().toAbsolutePath().getParent());
                Files.write(file.toPath(), bundled);
            } catch (IOException cannotWrite) {
                logger.warning("Could not write " + file.getName() + " (" + cannotWrite.getMessage() + "); using the defaults.");
            }
        }

        YamlConfiguration config = new YamlConfiguration();
        try {
            config.load(file);
        } catch (IOException | InvalidConfigurationException broken) {
            String problem = firstLine(broken.getMessage());
            logger.severe(file.getName() + " could not be read: " + problem);
            logger.severe("Running on the default settings until it is fixed; the file was not changed. Fix it, then reload.");
            YamlConfiguration fallback = new YamlConfiguration();
            copyInto(defaults, fallback);
            fallback.setDefaults(defaults);
            return new Loaded(fallback, List.of(), problem);
        }

        List<String> added = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (config.contains(key, true) || insideKeptFreeForm(key, freeForm, config)) {
                continue;
            }
            if (defaults.isConfigurationSection(key)) {
                config.createSection(key);
            } else {
                config.set(key, defaults.get(key));
                added.add(key);
            }
            config.setComments(key, defaults.getComments(key));
            config.setInlineComments(key, defaults.getInlineComments(key));
        }
        if (!added.isEmpty()) {
            try {
                config.save(file);
                logger.info("Added " + added.size() + " new setting(s) to " + file.getName() + ": " + String.join(", ", added));
            } catch (IOException cannotWrite) {
                logger.warning("Could not add new settings to " + file.getName() + " (" + cannotWrite.getMessage() + "); their defaults apply.");
            }
        }
        config.setDefaults(defaults);
        return new Loaded(config, added, null);
    }

    private static boolean insideKeptFreeForm(String key, java.util.Set<String> freeForm, YamlConfiguration config) {
        for (String section : freeForm) {
            if (key.startsWith(section + ".") && config.contains(section, true)) {
                return true;
            }
        }
        return false;
    }

    private static void copyInto(YamlConfiguration from, YamlConfiguration to) {
        for (String key : from.getKeys(true)) {
            if (!from.isConfigurationSection(key)) {
                to.set(key, from.get(key));
            }
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unknown error";
        }
        String trimmed = message.strip();
        int newline = trimmed.indexOf('\n');
        return newline < 0 ? trimmed : trimmed.substring(0, newline).strip();
    }
}

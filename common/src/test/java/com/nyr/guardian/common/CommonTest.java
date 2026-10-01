package com.nyr.guardian.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommonTest {

    private static final String DEFAULTS = """
        # Switch the whole plugin off without removing it.
        enabled: true

        limits:
          # An example number setting.
          max-cost: 39
          # Newer setting.
          cap-prior-work: true

        messages:
          prefix: "&8[&cNYR&8] "
          done: "{prefix}&aDone for {player}."
        """;

    @TempDir
    Path dir;

    private final List<String> logged = new ArrayList<>();

    private Logger logger() {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private ConfigFile.Loaded load(File file) {
        return ConfigFile.load(file, new ByteArrayInputStream(DEFAULTS.getBytes(StandardCharsets.UTF_8)), logger());
    }

    @Test
    void firstStartWritesTheBundledFileAsWritten() throws Exception {
        File file = dir.resolve("config.yml").toFile();
        ConfigFile.Loaded loaded = load(file);
        assertEquals(DEFAULTS, Files.readString(file.toPath()));
        assertEquals(39, loaded.config().getInt("limits.max-cost"));
        assertTrue(loaded.added().isEmpty());
        assertNull(loaded.problem());
    }

    @Test
    void anUpdateAddsNewSettingsAndKeepsEveryEdit() throws Exception {
        File file = dir.resolve("config.yml").toFile();
        Files.writeString(file.toPath(), """
            # my own note
            enabled: false
            limits:
              max-cost: 30
            messages:
              done: "custom {player}"
            """);
        ConfigFile.Loaded loaded = load(file);
        assertEquals(List.of("limits.cap-prior-work", "messages.prefix"), loaded.added());
        YamlConfiguration reread = YamlConfiguration.loadConfiguration(file);
        assertFalse(reread.getBoolean("enabled"));
        assertEquals(30, reread.getInt("limits.max-cost"));
        assertTrue(reread.getBoolean("limits.cap-prior-work"));
        assertEquals("custom {player}", reread.getString("messages.done"));
        String text = Files.readString(file.toPath());
        assertTrue(text.contains("# my own note"), text);
        assertTrue(text.contains("# Newer setting."), text);
    }

    @Test
    void aBrokenFileIsLeftAloneAndTheDefaultsRun() throws Exception {
        File file = dir.resolve("config.yml").toFile();
        String broken = "enabled: true\nlimits:\n  max-cost: [oops\n";
        Files.writeString(file.toPath(), broken);
        ConfigFile.Loaded loaded = load(file);
        assertNotNull(loaded.problem());
        assertEquals(broken, Files.readString(file.toPath()));
        assertEquals(39, loaded.config().getInt("limits.max-cost"));
        assertTrue(logged.stream().anyMatch(line -> line.startsWith("SEVERE") && line.contains("could not be read")), logged.toString());
    }

    @Test
    void colorsAndPlaceholders() {
        assertEquals("\u00a7aHi \u00a7x\u00a7f\u00a7f\u00a70\u00a70\u00a7a\u00a7abold", Text.color("&aHi &#FF00AAbold"));
        assertEquals("a & b", Text.color("a & b"));
        assertEquals("Kai took 3", Text.fill("{player} took {count}", "player", "Kai", "count", 3));
        assertEquals("Hi bold", Text.plain(Text.color("&aHi &#FF00AA&lbold")));
        Messages messages = new Messages(YamlConfiguration.loadConfiguration(new java.io.StringReader(DEFAULTS)).getConfigurationSection("messages"));
        assertEquals("\u00a78[\u00a7cNYR\u00a78] \u00a7aDone for Kai.", messages.format("done", "player", "Kai"));
        assertTrue(messages.format("nope").contains("missing message"));
    }

    @Test
    void versionsIncludingYearVersions() {
        assertEquals(List.of(1, 21, 11), Platform.parseVersion("git-Paper-123 (MC: 1.21.11)", "1.21.11-R0.1-SNAPSHOT"));
        assertEquals(List.of(26, 1, 2), Platform.parseVersion("26.1.2-74-e4e17fc (MC: 26.1.2)", "26.1.2.build.74-alpha"));
        assertEquals(List.of(1, 20, 6), Platform.parseVersion("4096-Spigot-abc", "1.20.6-R0.1-SNAPSHOT"));
        assertEquals(List.of(0), Platform.parseVersion(null, null));
        Platform p = new Platform(Platform.Software.PAPER, List.of(1, 21, 11));
        assertTrue(p.atLeast(1, 21));
        assertTrue(p.atLeast(1, 21, 11));
        assertFalse(p.atLeast(1, 21, 12));
        assertFalse(p.atLeast(26));
        assertTrue(new Platform(Platform.Software.PAPER, List.of(26, 1, 2)).atLeast(1, 21, 11));
        assertEquals("Paper 1.21.11", p.describe());
    }

    @Test
    void worldFilter() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader("worlds:\n  enabled: ['*']\n  disabled: [World_Nether]\n"));
        WorldFilter filter = WorldFilter.from(yaml.getConfigurationSection("worlds"));
        assertTrue(filter.allows("world"));
        assertFalse(filter.allows("world_nether"));
        WorldFilter only = WorldFilter.from(YamlConfiguration.loadConfiguration(new java.io.StringReader("w:\n  enabled: [survival]\n")).getConfigurationSection("w"));
        assertTrue(only.allows("Survival"));
        assertFalse(only.allows("world"));
        assertTrue(WorldFilter.from(null).allows("anything"));
    }

    @Test
    void alertsRespectTheirCooldown() throws Exception {
        File log = dir.resolve("alerts.log").toFile();
        Alerts alerts = new Alerts("test-alerts", "nyrtest.alerts", logger(), log, List::of);
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new java.io.StringReader("alerts:\n  cooldown-seconds: 60\n"));
        alerts.configure(yaml.getConfigurationSection("alerts"));
        assertTrue(alerts.send("kai:pearl", "\u00a7cKai tried it"));
        assertFalse(alerts.send("kai:pearl", "\u00a7cKai tried it again"));
        assertTrue(alerts.send("luna:pearl", "\u00a7cLuna tried it"));
        alerts.close();
        List<String> lines = Files.readAllLines(log.toPath());
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).endsWith("Kai tried it"));
    }
}

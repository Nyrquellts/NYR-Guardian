package com.nyr.guardian.common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/**
 * Staff alerts: chat for players with the alert permission, the console, and an append-only alerts.log. The same alert key
 * is sent at most once per cooldown, so a player hammering an exploit cannot flood staff chat.
 */
public final class Alerts {

    private static final long LOG_LIMIT_BYTES = 5L * 1024 * 1024;
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final String permission;
    private final Logger logger;
    private final File logFile;
    private final Supplier<Collection<? extends Player>> onlinePlayers;
    private final Map<String, Long> lastSent = new ConcurrentHashMap<>();
    private final ExecutorService writer;

    private volatile boolean enabled = true;
    private volatile boolean toPlayers = true;
    private volatile boolean toConsole = true;
    private volatile boolean toFile = true;
    private volatile long cooldownMillis = 10_000;

    public Alerts(String threadName, String permission, Logger logger, File logFile, Supplier<Collection<? extends Player>> onlinePlayers) {
        this.permission = permission;
        this.logger = logger;
        this.logFile = logFile;
        this.onlinePlayers = onlinePlayers;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Reads enabled, to-staff-chat, to-console, to-log-file and cooldown-seconds from the alerts section. */
    public void configure(ConfigurationSection section) {
        if (section == null) {
            return;
        }
        enabled = section.getBoolean("enabled", true);
        toPlayers = section.getBoolean("to-staff-chat", true);
        toConsole = section.getBoolean("to-console", true);
        toFile = section.getBoolean("to-log-file", true);
        cooldownMillis = Math.max(0, (long) (section.getDouble("cooldown-seconds", 10) * 1000));
    }

    public String permission() {
        return permission;
    }

    /**
     * Sends a coloured alert unless the same key was sent within the cooldown.
     *
     * @return whether it was sent
     */
    public boolean send(String key, String colored) {
        if (!enabled) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean[] fresh = {false};
        lastSent.compute(key, (k, previous) -> {
            if (previous == null || now - previous >= cooldownMillis) {
                fresh[0] = true;
                return now;
            }
            return previous;
        });
        if (!fresh[0]) {
            return false;
        }
        if (lastSent.size() > 4096) {
            lastSent.entrySet().removeIf(entry -> now - entry.getValue() >= cooldownMillis);
        }
        if (toPlayers) {
            for (Player player : onlinePlayers.get()) {
                if (player.hasPermission(permission)) {
                    player.sendMessage(colored);
                }
            }
        }
        String plain = Text.plain(colored);
        if (toConsole) {
            logger.info(plain);
        }
        if (toFile && logFile != null) {
            String line = "[" + LocalDateTime.now().format(STAMP) + "] " + plain + System.lineSeparator();
            writer.execute(() -> append(line));
        }
        return true;
    }

    private void append(String line) {
        try {
            Files.createDirectories(logFile.toPath().toAbsolutePath().getParent());
            if (logFile.length() > LOG_LIMIT_BYTES) {
                File rotated = new File(logFile.getParentFile(), logFile.getName() + ".1");
                Files.move(logFile.toPath(), rotated.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(logFile.toPath(), line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException cannotWrite) {
            logger.warning("Could not write " + logFile.getName() + ": " + cannotWrite.getMessage());
        }
    }

    /** Writes what is queued, then stops the writer thread. */
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(2, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
    }
}

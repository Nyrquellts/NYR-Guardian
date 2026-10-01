package com.nyr.guardian.chunkhopper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * plugins/NYR-ChunkHopper/hoppers.yml: every Chunk Hopper there is, loaded or not, for /chunkhopper list and the per-player
 * limit. The world is the truth; this is an index of it, checked whenever a listed chunk loads. Written off the server
 * threads, one write at a time, through a temporary file so a crash never leaves half a file.
 */
final class Registry {

    record Entry(UUID world, String worldName, int x, int y, int z, UUID owner, String ownerName) {

        ChunkId chunk() {
            return new ChunkId(world, x >> 4, z >> 4);
        }
    }

    private final File file;
    private final Logger logger;
    private final Map<ChunkId, Entry> entries = new ConcurrentHashMap<>();
    private final ExecutorService writer;
    private final AtomicBoolean queued = new AtomicBoolean();
    private final AtomicLong changes = new AtomicLong();
    private long written;

    Registry(File file, Logger logger) {
        this.file = file;
        this.logger = logger;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "NYR-ChunkHopper-registry");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Reads the file; a missing or broken file leaves the index empty (it refills as chunks load). */
    synchronized void load() {
        entries.clear();
        if (!file.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException broken) {
            logger.warning(file.getName() + " could not be read (" + broken.getMessage() + "); Chunk Hoppers are listed again as their chunks load.");
            return;
        }
        for (Map<?, ?> raw : yaml.getMapList("hoppers")) {
            try {
                UUID world = UUID.fromString(String.valueOf(raw.get("world")));
                Object ownerText = raw.get("owner");
                UUID owner = ownerText == null ? null : UUID.fromString(String.valueOf(ownerText));
                Entry entry = new Entry(world, String.valueOf(raw.get("world-name")), number(raw.get("x")), number(raw.get("y")),
                    number(raw.get("z")), owner, raw.get("owner-name") == null ? "?" : String.valueOf(raw.get("owner-name")));
                entries.put(entry.chunk(), entry);
            } catch (RuntimeException broken) {
                logger.warning("Skipped a broken entry in " + file.getName() + ": " + raw);
            }
        }
        written = changes.get();
    }

    private static int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    Entry get(ChunkId chunk) {
        return entries.get(chunk);
    }

    void put(Entry entry) {
        Entry before = entries.put(entry.chunk(), entry);
        if (!entry.equals(before)) {
            changed();
        }
    }

    /** Removes the chunk's entry if it is the one at {@code x y z}. */
    void remove(ChunkId chunk, int x, int y, int z) {
        Entry entry = entries.get(chunk);
        if (entry != null && entry.x() == x && entry.y() == y && entry.z() == z && entries.remove(chunk, entry)) {
            changed();
        }
    }

    void removeChunk(ChunkId chunk) {
        if (entries.remove(chunk) != null) {
            changed();
        }
    }

    int size() {
        return entries.size();
    }

    int count(UUID owner) {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (Objects.equals(entry.owner(), owner)) {
                count++;
            }
        }
        return count;
    }

    List<Entry> owned(UUID owner) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (Objects.equals(entry.owner(), owner)) {
                out.add(entry);
            }
        }
        out.sort(Comparator.comparing(Entry::worldName).thenComparingInt(Entry::x).thenComparingInt(Entry::z).thenComparingInt(Entry::y));
        return out;
    }

    List<Entry> all() {
        return new ArrayList<>(entries.values());
    }

    /** The owner UUID of an entry whose owner name matches, ignoring case; null when none does. */
    UUID ownerNamed(String name) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Entry entry : entries.values()) {
            if (entry.owner() != null && entry.ownerName() != null && entry.ownerName().toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.owner();
            }
        }
        return null;
    }

    private void changed() {
        changes.incrementAndGet();
        if (queued.compareAndSet(false, true)) {
            try {
                writer.execute(() -> {
                    queued.set(false);
                    write();
                });
            } catch (RejectedExecutionException closed) {
                queued.set(false);
            }
        }
    }

    /** Writes the file when anything changed since the last write. */
    synchronized void write() {
        long now = changes.get();
        if (now == written && file.isFile()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Entry entry : entries.values()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("world", entry.world().toString());
            map.put("world-name", entry.worldName());
            map.put("x", entry.x());
            map.put("y", entry.y());
            map.put("z", entry.z());
            if (entry.owner() != null) {
                map.put("owner", entry.owner().toString());
            }
            map.put("owner-name", entry.ownerName());
            list.add(map);
        }
        yaml.set("hoppers", list);
        String text = "# NYR ChunkHopper's own list of every Chunk Hopper, for /chunkhopper list and the per-player limit.\n"
            + "# The plugin writes it; each entry is checked against the world whenever its chunk loads.\n" + yaml.saveToString();
        try {
            Files.createDirectories(file.toPath().toAbsolutePath().getParent());
            File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.writeString(temporary.toPath(), text, StandardCharsets.UTF_8);
            try {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException notHere) {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            written = now;
        } catch (IOException cannotWrite) {
            logger.warning("Could not write " + file.getName() + ": " + cannotWrite.getMessage());
        }
    }

    /** Writes what is pending, then stops the writer thread. */
    void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }
        write();
    }
}

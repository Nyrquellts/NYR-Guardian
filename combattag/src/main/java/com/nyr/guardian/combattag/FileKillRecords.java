package com.nyr.guardian.combattag;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Kill records as plugins/NYR-CombatTagPro/data/killed/&lt;uuid&gt;.yml. Each is written to a temporary file, forced to the
 * disk, then moved over the old one in one step, so a crash leaves either the old record or the new one, never half of one.
 * Which owners have a record is kept in memory, so a join costs no disk access unless there is something to read.
 */
final class FileKillRecords implements KillRecordStore {

    private final Path dir;
    private final Logger logger;
    private final Set<UUID> known = ConcurrentHashMap.newKeySet();

    FileKillRecords(Path dir, Logger logger) {
        this.dir = dir;
        this.logger = logger;
        index();
    }

    /** Reads which records exist; leftovers of an interrupted write are removed. */
    void index() {
        known.clear();
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                if (name.endsWith(".yml.tmp")) {
                    Files.deleteIfExists(file);
                    continue;
                }
                if (name.endsWith(".yml")) {
                    try {
                        known.add(UUID.fromString(name.substring(0, name.length() - 4)));
                    } catch (IllegalArgumentException notOurs) {
                        // a file someone else put here
                    }
                }
            }
        } catch (IOException unreadable) {
            logger.severe("Could not read " + dir + ": " + unreadable.getMessage());
        }
    }

    Path fileOf(UUID owner) {
        return dir.resolve(owner + ".yml");
    }

    @Override
    public void write(KillRecord record) throws IOException {
        Files.createDirectories(dir);
        Path target = fileOf(record.owner());
        Path temp = dir.resolve(record.owner() + ".yml.tmp");
        byte[] bytes = record.toYaml().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        syncDirectory();
        known.add(record.owner());
    }

    @Override
    public KillRecord read(UUID owner) throws IOException {
        if (!known.contains(owner)) {
            return null;
        }
        Path file = fileOf(owner);
        if (!Files.isRegularFile(file)) {
            known.remove(owner);
            return null;
        }
        return KillRecord.fromYaml(Files.readString(file, StandardCharsets.UTF_8));
    }

    @Override
    public void delete(UUID owner) throws IOException {
        Files.deleteIfExists(fileOf(owner));
        syncDirectory();
        known.remove(owner);
    }

    @Override
    public int pending() {
        return known.size();
    }

    /** Makes the rename itself durable where the platform allows opening a directory (Linux does; Windows does not). */
    private void syncDirectory() {
        try (FileChannel directory = FileChannel.open(dir, StandardOpenOption.READ)) {
            directory.force(true);
        } catch (IOException | UnsupportedOperationException notSupported) {
            // Windows cannot open a directory for this; the rename is still all-or-nothing there
        }
    }
}

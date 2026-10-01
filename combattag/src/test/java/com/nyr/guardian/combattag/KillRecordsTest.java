package com.nyr.guardian.combattag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KillRecordsTest {

    @TempDir
    Path dir;

    private final Logger logger = Logger.getAnonymousLogger();

    @Test
    void aRecordRoundTripsAndIsGoneAfterDelete() throws IOException {
        FileKillRecords records = new FileKillRecords(dir.resolve("killed"), logger);
        UUID owner = UUID.randomUUID();
        UUID killer = UUID.randomUUID();
        assertNull(records.read(owner), "no record yet");
        KillRecord written = new KillRecord(owner, "Kai", "Luna", killer, 1_758_000_000_000L, "world", 10.25, 64, -3.5, true, false, 6, 0);
        records.write(written);
        assertEquals(1, records.pending());
        assertEquals(written, records.read(owner));
        assertTrue(Files.readString(dir.resolve("killed").resolve(owner + ".yml")).contains("killer: Luna"));
        assertFalse(Files.exists(dir.resolve("killed").resolve(owner + ".yml.tmp")), "no temporary file is left behind");
        records.delete(owner);
        assertNull(records.read(owner));
        assertEquals(0, records.pending());
    }

    @Test
    void aRestartFindsTheRecordsAndDropsHalfWrittenOnes() throws IOException {
        Path killed = dir.resolve("killed");
        UUID owner = UUID.randomUUID();
        new FileKillRecords(killed, logger).write(new KillRecord(owner, "Kai", "lava", null, 0, "world", 0, 64, 0, true, true, 1, 7));
        Files.writeString(killed.resolve(UUID.randomUUID() + ".yml.tmp"), "half a reco", StandardCharsets.UTF_8);
        Files.writeString(killed.resolve("notes.yml"), "an admin's file", StandardCharsets.UTF_8);
        FileKillRecords restarted = new FileKillRecords(killed, logger);
        assertEquals(1, restarted.pending(), "only the real record counts");
        assertEquals("lava", restarted.read(owner).killer());
        assertNull(restarted.read(owner).killerId());
        try (var files = Files.list(killed)) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString().endsWith(".tmp")), "the half-written file is removed");
        }
    }

    @Test
    void aRecordThatDoesNotSayWhatDroppedIsReadAsEverythingDropped() throws IOException {
        UUID owner = UUID.randomUUID();
        KillRecord read = KillRecord.fromYaml("owner: " + owner + "\nkiller: Luna\n");
        assertTrue(read.itemsDropped() && read.experienceDropped(), "the side that can never duplicate items");
        assertThrows(IOException.class, () -> KillRecord.fromYaml("killer: Luna\n"), "a record without an owner is refused");
        assertThrows(IOException.class, () -> KillRecord.fromYaml("owner: not-a-uuid\n"));
    }

    @Test
    void writingOverAnOldRecordReplacesIt() throws IOException {
        FileKillRecords records = new FileKillRecords(dir.resolve("killed"), logger);
        UUID owner = UUID.randomUUID();
        records.write(new KillRecord(owner, "Kai", "Luna", null, 0, "world", 0, 64, 0, true, true, 1, 7));
        records.write(new KillRecord(owner, "Kai", "Mira", null, 0, "world", 0, 64, 0, true, true, 2, 14));
        assertEquals("Mira", records.read(owner).killer());
        assertEquals(1, records.pending());
    }
}

package com.nyr.guardian.combattag;

import java.io.IOException;
import java.util.UUID;

/** Where kill records live. A write returns only once the record would survive a crash of the whole machine. */
interface KillRecordStore {

    void write(KillRecord record) throws IOException;

    /** The owner's record, or null when they have none. */
    KillRecord read(UUID owner) throws IOException;

    void delete(UUID owner) throws IOException;

    /** How many records wait for their owner to join. */
    int pending();
}

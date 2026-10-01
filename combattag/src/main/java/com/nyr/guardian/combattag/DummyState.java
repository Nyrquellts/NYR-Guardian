package com.nyr.guardian.combattag;

import java.io.IOException;
import java.util.UUID;
import org.bukkit.entity.Entity;

/**
 * One owner's combat-log dummy and the one lock that decides its fate. On Folia the dummy's death, its owner's join and its
 * timer run on different threads at once; exactly one of STANDING -> KILLED, STANDING -> REJOINED and STANDING -> EXPIRED
 * wins, and only the winner acts. A kill counts only once its record is on disk, and a join that finds the dummy killed waits
 * for that write, so no order of events both drops the items and hands them back.
 */
final class DummyState {

    enum Phase { STANDING, KILLED, REJOINED, EXPIRED, FAILED }

    enum KillOutcome {
        /** The record is on disk: drop the items. */
        KILLED,
        /** The owner came back, the dummy ran out of time or already died: drop nothing. */
        LOST,
        /** The record could not be written: drop nothing, the owner keeps everything. */
        WRITE_FAILED
    }

    /** What a join finds: nothing to do, the standing dummy to take back, or a kill to apply. */
    record Join(Kind kind, KillRecord record) {
        enum Kind { NOTHING, RETURN, APPLY }

        static final Join NOTHING = new Join(Kind.NOTHING, null);
        static final Join RETURN = new Join(Kind.RETURN, null);
    }

    final UUID owner;
    final String ownerName;
    final Snapshot snapshot;
    final Entity body;
    final UUID bodyId;
    final String bodyKind;
    final long spawnedAt;
    final long until;
    /** The dummy's health as last read on its own thread; what a returning owner gets if the dummy is gone by then. */
    volatile double health;

    private Phase phase = Phase.STANDING;
    private KillRecord record;
    private Exception failure;

    DummyState(Snapshot snapshot, Entity body, String bodyKind, long spawnedAt, long until) {
        this.owner = snapshot.id();
        this.ownerName = snapshot.name();
        this.snapshot = snapshot;
        this.body = body;
        this.bodyId = body == null ? null : body.getUniqueId();
        this.bodyKind = bodyKind;
        this.spawnedAt = spawnedAt;
        this.until = until;
        this.health = snapshot.health();
    }

    synchronized Phase phase() {
        return phase;
    }

    synchronized KillRecord record() {
        return record;
    }

    synchronized Exception failure() {
        return failure;
    }

    /**
     * STANDING -> KILLED. The record is written (durably) while this owner's lock is held; only when the write succeeded does
     * the caller drop anything.
     */
    synchronized KillOutcome kill(KillRecord killed, KillRecordStore store) {
        if (phase != Phase.STANDING) {
            return KillOutcome.LOST;
        }
        try {
            store.write(killed);
        } catch (IOException | RuntimeException writeFailed) {
            phase = Phase.FAILED;
            failure = writeFailed;
            return KillOutcome.WRITE_FAILED;
        }
        phase = Phase.KILLED;
        record = killed;
        return KillOutcome.KILLED;
    }

    /** The owner joined: STANDING -> REJOINED, or the kill to apply. */
    synchronized Join join() {
        return switch (phase) {
            case STANDING -> {
                phase = Phase.REJOINED;
                yield Join.RETURN;
            }
            case KILLED -> new Join(Join.Kind.APPLY, record);
            default -> Join.NOTHING;
        };
    }

    /** STANDING -> EXPIRED: the dummy survived its time (or the plugin is stopping), the owner keeps everything. */
    synchronized boolean expire() {
        if (phase != Phase.STANDING) {
            return false;
        }
        phase = Phase.EXPIRED;
        return true;
    }
}

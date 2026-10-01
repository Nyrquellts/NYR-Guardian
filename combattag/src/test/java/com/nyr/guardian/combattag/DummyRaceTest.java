package com.nyr.guardian.combattag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * The three-way race of Folia, from real threads: the dummy dies on its region's thread, its owner joins on another, its timer
 * fires on a third. Whatever the order, exactly one of them wins, and the items are never both dropped and handed back.
 */
class DummyRaceTest {

    /** A store that is slow to write (so the others arrive mid-write) and remembers what is on "disk". */
    private static final class SlowStore implements KillRecordStore {
        final Map<UUID, KillRecord> disk = new ConcurrentHashMap<>();
        final boolean fail;

        SlowStore(boolean fail) {
            this.fail = fail;
        }

        @Override
        public void write(KillRecord record) throws IOException {
            spin(ThreadLocalRandom.current().nextInt(0, 200_000));
            if (fail) {
                throw new IOException("disk full");
            }
            disk.put(record.owner(), record);
        }

        @Override
        public KillRecord read(UUID owner) {
            return disk.get(owner);
        }

        @Override
        public void delete(UUID owner) {
            disk.remove(owner);
        }

        @Override
        public int pending() {
            return disk.size();
        }
    }

    private static void spin(int nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    private static Snapshot snapshot(UUID owner) {
        return new Snapshot(owner, "Kai", null, new ItemStack[0], new ItemStack[0], null, null, 3, 12, 20);
    }

    private static KillRecord record(UUID owner) {
        return new KillRecord(owner, "Kai", "Luna", UUID.randomUUID(), 0, "world", 0, 64, 0, true, true, 4, 21);
    }

    /** One round: death, join and timer released together; returns what each saw. */
    private record Round(DummyState.KillOutcome kill, DummyState.Join join, boolean expired, boolean dropped, boolean recordOnDiskAtApply) {
    }

    private static Round race(boolean failingDisk) throws InterruptedException {
        UUID owner = UUID.randomUUID();
        DummyState state = new DummyState(snapshot(owner), null, "test", 0, 30_000);
        SlowStore store = new SlowStore(failingDisk);
        CountDownLatch go = new CountDownLatch(1);
        AtomicReference<DummyState.KillOutcome> kill = new AtomicReference<>();
        AtomicReference<DummyState.Join> join = new AtomicReference<>();
        AtomicBoolean expired = new AtomicBoolean();
        AtomicBoolean dropped = new AtomicBoolean();
        AtomicBoolean recordOnDisk = new AtomicBoolean(true);
        List<Thread> threads = new ArrayList<>();
        threads.add(new Thread(() -> {
            await(go);
            DummyState.KillOutcome outcome = state.kill(record(owner), store);
            kill.set(outcome);
            if (outcome == DummyState.KillOutcome.KILLED) {
                dropped.set(true);
            }
        }, "dummy-region"));
        threads.add(new Thread(() -> {
            await(go);
            DummyState.Join found = state.join();
            join.set(found);
            if (found.kind() == DummyState.Join.Kind.APPLY) {
                recordOnDisk.set(store.read(owner) != null);
            }
        }, "owner-region"));
        threads.add(new Thread(() -> {
            await(go);
            expired.set(state.expire());
        }, "global-region"));
        threads.forEach(Thread::start);
        go.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
            assertFalse(thread.isAlive(), thread.getName() + " finished");
        }
        return new Round(kill.get(), join.get(), expired.get(), dropped.get(), recordOnDisk.get());
    }

    private static void await(CountDownLatch go) {
        try {
            go.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void deathJoinAndTimerNeverBothDropAndRestore() throws InterruptedException {
        int killed = 0;
        int returned = 0;
        int expiredFirst = 0;
        for (int round = 0; round < 3000; round++) {
            Round r = race(false);
            boolean restored = r.join().kind() == DummyState.Join.Kind.RETURN;
            int winners = (r.kill() == DummyState.KillOutcome.KILLED ? 1 : 0) + (restored ? 1 : 0) + (r.expired() ? 1 : 0);
            assertEquals(1, winners, "exactly one transition wins: " + r);
            assertFalse(r.dropped() && restored, "the items are never both dropped and handed back: " + r);
            if (r.join().kind() == DummyState.Join.Kind.APPLY) {
                assertTrue(r.dropped(), "a join only applies a kill that happened: " + r);
                assertTrue(r.recordOnDiskAtApply(), "and only once its record is on disk: " + r);
                assertNotNull(r.join().record());
            }
            if (r.dropped()) {
                killed++;
            } else if (restored) {
                returned++;
            } else {
                expiredFirst++;
            }
        }
        // Each order happened: the race was really run, not decided the same way every time.
        assertTrue(killed > 0 && returned > 0 && expiredFirst > 0,
            "every winner occurred: killed " + killed + ", returned " + returned + ", expired " + expiredFirst);
    }

    @Test
    void aFailedWriteMeansNoDropAndNothingToApply() throws InterruptedException {
        for (int round = 0; round < 500; round++) {
            Round r = race(true);
            assertFalse(r.dropped(), "nothing drops when the record could not be written");
            assertTrue(r.join().kind() != DummyState.Join.Kind.APPLY, "and no join takes anything");
        }
    }
}

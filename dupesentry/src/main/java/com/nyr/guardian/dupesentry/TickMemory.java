package com.nyr.guardian.dupesentry;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Block positions remembered for the rest of the tick in which something happened there, and forgotten on the next tick of
 * whatever owns them: on Folia the region that owns the position runs the clean-up on its own thread, elsewhere the main
 * thread's next tick does. Positions are kept per world.
 */
final class TickMemory {

    /** A block position in one world. */
    record Spot(UUID world, int x, int y, int z) {

        static Spot of(Block block) {
            return new Spot(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        static Spot of(World world, Location location) {
            return new Spot(world.getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
    }

    /**
     * One remembered fact about a spot. Marks compare by identity, so the clean-up of an earlier tick never forgets what a
     * later tick remembered at the same spot.
     */
    static final class Mark {

        private final Material material;
        private int count = 1;
        private volatile boolean released;

        Mark(Material material) {
            this.material = material;
        }

        Material material() {
            return material;
        }

        /** Something legitimate happened at the spot after all (a player placed a block there): leave it alone. */
        void release() {
            released = true;
        }

        boolean released() {
            return released;
        }

        /** How many times this happened at the spot in this tick; the spot's owning thread is the only one counting. */
        synchronized int count() {
            return count;
        }

        synchronized int increment() {
            return ++count;
        }
    }

    private final Map<Spot, Mark> marks = new ConcurrentHashMap<>();
    private final BiConsumer<Location, Runnable> nextTick;

    /** {@code nextTick} runs a task on the next tick of the region that owns the location. */
    TickMemory(BiConsumer<Location, Runnable> nextTick) {
        this.nextTick = nextTick;
    }

    void remember(Map<Spot, Mark> batch, Location anchor) {
        remember(batch, anchor, null);
    }

    /** Remembers the batch until the next tick at {@code anchor}; then forgets it and hands it to {@code expired}, if given. */
    void remember(Map<Spot, Mark> batch, Location anchor, Consumer<Map<Spot, Mark>> expired) {
        if (batch.isEmpty()) {
            return;
        }
        marks.putAll(batch);
        nextTick.accept(anchor, () -> {
            batch.forEach(marks::remove);
            if (expired != null) {
                expired.accept(batch);
            }
        });
    }

    Mark get(Spot spot) {
        return marks.isEmpty() ? null : marks.get(spot);
    }

    /** True while anything is remembered: the cheap first test on hot events. */
    boolean isEmpty() {
        return marks.isEmpty();
    }

    int size() {
        return marks.size();
    }

    void forget(Spot spot) {
        marks.remove(spot);
    }

    void clear() {
        marks.clear();
    }
}

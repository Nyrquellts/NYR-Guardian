package com.nyr.guardian.chunkhopper;

import com.tcoded.folialib.impl.PlatformScheduler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The Chunk Hoppers whose chunks are loaded, found through each chunk's pointer when it loads, and the one place that
 * creates, validates and forgets them. When a chunk loads, its Chunk Hopper is checked against the sale journal before
 * the hopper can move a single item (see {@link SaleJournal}). Every method that touches a block runs on the thread that
 * owns it.
 */
final class Hoppers {

    /** What recovery found for one hopper: sales whose removal the world lost although they may have been paid. */
    record Rollback(HopperRecord record, World world, int sales, int items, int taken, double paid) {
    }

    /** How long after a save (or an unload) its sales are taken as written: the chunk data reaches disk well before. */
    private static final long SAVED_AFTER_SECONDS = 60;

    private final Server server;
    private final PlatformScheduler scheduler;
    private final Logger logger;
    final HopperStore store;
    final Registry registry;
    final Stats stats;
    final SaleJournal journal;
    private final Predicate<String> worldAllowed;
    private final Map<ChunkId, HopperRecord> loaded = new ConcurrentHashMap<>();
    /** Chunk Hoppers broken or forgotten since the last save, by id: their recorded sales go once a save has written that. */
    private final Map<UUID, UUID> goneSinceSave = new ConcurrentHashMap<>();
    private volatile Consumer<HopperRecord> whenGone = record -> { };
    private volatile Consumer<Rollback> whenRolledBack = rollback -> { };

    Hoppers(Server server, PlatformScheduler scheduler, Logger logger, HopperStore store, Registry registry, Stats stats,
            SaleJournal journal, Predicate<String> worldAllowed) {
        this.server = server;
        this.scheduler = scheduler;
        this.logger = logger;
        this.store = store;
        this.registry = registry;
        this.stats = stats;
        this.journal = journal;
        this.worldAllowed = worldAllowed;
    }

    /** Called with every record that stops being a Chunk Hopper (broken, replaced, unloaded), on the hopper's thread. */
    void whenGone(Consumer<HopperRecord> listener) {
        this.whenGone = listener;
    }

    /** Called when a chunk loads with sales the saved world does not have (the server crashed after they were paid). */
    void whenRolledBack(Consumer<Rollback> listener) {
        this.whenRolledBack = listener;
    }

    // ------------------------------------------------------------------ lookups (any thread)

    /** The loaded Chunk Hopper of the chunk holding block x/z of this world, or null. */
    HopperRecord at(World world, int blockX, int blockZ) {
        if (loaded.isEmpty()) {
            return null;
        }
        return loaded.get(ChunkId.of(world, blockX, blockZ));
    }

    /** The loaded Chunk Hopper standing at this block, or null. */
    HopperRecord at(Block block) {
        HopperRecord record = at(block.getWorld(), block.getX(), block.getZ());
        return record != null && record.at(block) ? record : null;
    }

    HopperRecord in(ChunkId chunk) {
        return loaded.get(chunk);
    }

    /** Whether the record is still the one this chunk's Chunk Hopper is known by. */
    boolean current(HopperRecord record) {
        return loaded.get(record.chunk) == record;
    }

    Collection<HopperRecord> all() {
        return new ArrayList<>(loaded.values());
    }

    int loadedCount() {
        return loaded.size();
    }

    World world(HopperRecord record) {
        return server.getWorld(record.world);
    }

    boolean worldAllowed(World world) {
        return worldAllowed.test(world.getName());
    }

    // ------------------------------------------------------------------ threads

    /** Runs the task on the thread that owns the record's block: now if this is it, otherwise as soon as that thread can. */
    void runAt(HopperRecord record, Runnable task) {
        World world = world(record);
        if (world == null) {
            return;
        }
        Location at = record.center(world);
        if (scheduler.isOwnedByCurrentRegion(at)) {
            task.run();
        } else {
            scheduler.runAtLocation(at, ignored -> task.run());
        }
    }

    // ------------------------------------------------------------------ discovery

    /**
     * A chunk loaded: when it points at a Chunk Hopper, hoppers.yml lists one in it, or the sale journal has sales there,
     * the hopper is read now, before it ticks (a rolled-back sale must be taken out again before the hopper can pass the
     * items on). Anything that goes wrong reading it here is retried a tick later.
     */
    void chunkLoaded(Chunk chunk) {
        ChunkId id = ChunkId.of(chunk);
        if (loaded.containsKey(id) || (store.marked(chunk) == null && registry.get(id) == null && journal.hoppersIn(id).isEmpty())) {
            return;
        }
        try {
            discover(chunk);
        } catch (RuntimeException notNow) {
            logger.log(Level.FINE, "Reading the Chunk Hopper of chunk " + id + " as it loaded failed; trying again next tick", notNow);
            World world = chunk.getWorld();
            int cx = chunk.getX();
            int cz = chunk.getZ();
            scheduler.runAtLocation(new Location(world, (cx << 4) + 8, world.getMinHeight(), (cz << 4) + 8), ignored -> {
                if (world.isChunkLoaded(cx, cz)) {
                    discover(world.getChunkAt(cx, cz));
                }
            });
        }
    }

    /**
     * Reads what a loaded chunk, hoppers.yml and the sale journal know about its Chunk Hopper, keeps them in step with the
     * block, takes out again what a rolled-back sale sold, and loads the hopper when it still is one.
     */
    void discover(Chunk chunk) {
        World world = chunk.getWorld();
        ChunkId id = ChunkId.of(chunk);
        if (loaded.containsKey(id) || !worldAllowed(world)) {
            return;
        }
        int[] marked = store.marked(chunk);
        Registry.Entry listed = registry.get(id);
        List<UUID> journaled = journal.hoppersIn(id);
        if (marked == null && listed == null) {
            // Sales of a Chunk Hopper that the saved world no longer has here: they can never apply.
            journaled.forEach(journal::forget);
            return;
        }
        int x = marked != null ? marked[0] : listed.x();
        int y = marked != null ? marked[1] : listed.y();
        int z = marked != null ? marked[2] : listed.z();
        if (x >> 4 != chunk.getX() || z >> 4 != chunk.getZ() || y < world.getMinHeight() || y >= world.getMaxHeight()) {
            store.unmark(chunk, x, y, z);
            registry.removeChunk(id);
            journaled.forEach(journal::forget);
            stats.forgotten.increment();
            return;
        }
        HopperStore.Data data = store.read(world.getBlockAt(x, y, z));
        if (data == null) {
            // The hopper is gone (removed while the plugin was off, by WorldEdit, or the world was rolled back).
            store.unmark(chunk, x, y, z);
            registry.removeChunk(id);
            journaled.forEach(journal::forget);
            stats.forgotten.increment();
            return;
        }
        if (marked == null || marked[0] != x || marked[1] != y || marked[2] != z) {
            store.mark(chunk, x, y, z);
        }
        HopperRecord record = new HopperRecord(world.getUID(), x, y, z, data);
        for (UUID other : journaled) {
            if (!other.equals(record.id)) {
                journal.forget(other);
            }
        }
        if (loaded.putIfAbsent(id, record) != null) {
            return;
        }
        settleSales(record, world);
        if (record.newId) {
            save(record, world);
        }
        registry.put(entry(record, world));
    }

    /**
     * Compares the hopper's saved sale number with the journal. A recorded sale above it was paid (or may have been)
     * while the world that took its stacks out was never saved: those stacks are taken out again, whatever of them is
     * there, and the hopper's number catches up. Sales at or below it are in the saved world and are forgotten.
     */
    private void settleSales(HopperRecord record, World world) {
        List<SaleJournal.Entry> entries = journal.entries(record.id);
        if (entries.isEmpty()) {
            return;
        }
        long saved = record.saleSeq();
        long newest = saved;
        List<SaleJournal.Entry> undone = new ArrayList<>();
        for (SaleJournal.Entry entry : entries) {
            newest = Math.max(newest, entry.seq());
            if (entry.seq() > saved && !entry.voided()) {
                undone.add(entry);
            }
        }
        if (!undone.isEmpty()) {
            BlockState state = world.getBlockAt(record.x, record.y, record.z).getState();
            if (state instanceof Hopper hopper) {
                Inventory inventory = hopper.getInventory();
                int items = 0;
                int taken = 0;
                double paid = 0;
                for (SaleJournal.Entry entry : undone) {
                    paid += entry.amount();
                    for (ItemStack stack : entry.stacks()) {
                        items += stack.getAmount();
                        HashMap<Integer, ItemStack> missing = inventory.removeItem(stack.clone());
                        int notThere = 0;
                        for (ItemStack left : missing.values()) {
                            notThere += left.getAmount();
                        }
                        taken += stack.getAmount() - notThere;
                    }
                }
                record.saleSeq(newest);
                save(record, world);
                stats.rolledBackSales.add(undone.size());
                stats.rolledBackItems.add(taken);
                whenRolledBack.accept(new Rollback(record, world, undone.size(), items, taken, paid));
            }
        } else if (newest > saved) {
            // Only sales the economy refused are above the saved number: their stacks are where they belong.
            record.saleSeq(newest);
            save(record, world);
        }
        journal.saved(record.id, newest);
    }

    /**
     * After a start or reload: every chunk hoppers.yml names is looked at on its own thread (at once where this thread
     * owns it, as at server start, before any hopper has ticked), and where one thread may read every world every loaded
     * chunk's pointer too, which finds hoppers even if hoppers.yml was lost.
     */
    void resume(boolean scanLoadedChunks) {
        for (Registry.Entry entry : registry.all()) {
            World world = server.getWorld(entry.world());
            if (world == null) {
                continue;
            }
            int cx = entry.x() >> 4;
            int cz = entry.z() >> 4;
            Location at = new Location(world, entry.x() + 0.5, entry.y(), entry.z() + 0.5);
            Runnable look = () -> {
                if (world.isChunkLoaded(cx, cz)) {
                    discover(world.getChunkAt(cx, cz));
                }
            };
            if (scheduler.isOwnedByCurrentRegion(at)) {
                look.run();
            } else {
                scheduler.runAtLocation(at, ignored -> look.run());
            }
        }
        if (scanLoadedChunks) {
            for (World world : server.getWorlds()) {
                if (!worldAllowed(world)) {
                    continue;
                }
                for (Chunk chunk : world.getLoadedChunks()) {
                    if (store.marked(chunk) != null || !journal.hoppersIn(ChunkId.of(chunk)).isEmpty()) {
                        discover(chunk);
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ one hopper (on its own thread)

    /**
     * The hopper's current state when the record's block still is this Chunk Hopper; otherwise the record is forgotten and
     * null returned. The chunk must be loaded. Use the state's live inventory freely, but never {@code update()} it
     * afterwards (see {@link HopperStore#write}).
     */
    Hopper live(HopperRecord record, World world) {
        Block block = world.getBlockAt(record.x, record.y, record.z);
        if (block.getType() == Material.HOPPER) {
            BlockState state = block.getState();
            if (state instanceof Hopper hopper && store.isChunkHopper(hopper)) {
                return hopper;
            }
        }
        forget(record, world);
        return null;
    }

    /** A Chunk Hopper was just placed: its TileState, its chunk and hoppers.yml learn about it. Null when the block is not a hopper. */
    HopperRecord create(Block block, Player owner, boolean autoSell) {
        World world = block.getWorld();
        HopperRecord record = new HopperRecord(world.getUID(), block.getX(), block.getY(), block.getZ(),
            new HopperStore.Data(null, owner.getUniqueId(), owner.getName(), Filter.EMPTY, autoSell, 0, 0, 0));
        if (!store.write(block, record, true)) {
            return null;
        }
        store.mark(block.getChunk(), block.getX(), block.getY(), block.getZ());
        HopperRecord before = loaded.put(record.chunk, record);
        if (before != null && before != record) {
            whenGone.accept(before);
        }
        registry.put(entry(record, world));
        stats.placed.increment();
        return record;
    }

    /** The hopper is being broken: its chunk pointer, its entry and its record go. */
    void removed(HopperRecord record, World world) {
        drop(record, world);
        stats.removed.increment();
    }

    /** The record's block is no longer this Chunk Hopper. */
    void forget(HopperRecord record, World world) {
        drop(record, world);
        stats.forgotten.increment();
    }

    private void drop(HopperRecord record, World world) {
        loaded.remove(record.chunk, record);
        if (world != null && world.isChunkLoaded(record.chunk.x(), record.chunk.z())) {
            store.unmark(world.getChunkAt(record.chunk.x(), record.chunk.z()), record.x, record.y, record.z);
        }
        registry.remove(record.chunk, record.x, record.y, record.z);
        // Its recorded sales stay until a save has the hopper gone: a crash before that brings the hopper back.
        goneSinceSave.put(record.id, record.world);
        whenGone.accept(record);
    }

    /** Writes the record (settings, counters, sale number) into its TileState; a hopper that is no longer one is forgotten. */
    void save(HopperRecord record, World world) {
        record.statsWritten();
        if (!store.write(world.getBlockAt(record.x, record.y, record.z), record, false)) {
            forget(record, world);
        }
    }

    /** Writes the counters when they changed; only when the chunk is loaded. */
    void flush(HopperRecord record) {
        if (!record.statsDirty()) {
            return;
        }
        World world = world(record);
        if (world != null && world.isChunkLoaded(record.chunk.x(), record.chunk.z())) {
            save(record, world);
        }
    }

    /**
     * A chunk is unloading, which saves it: its Chunk Hopper's counters are written, its record put away until the chunk
     * loads again, and its recorded sales forgotten once that save has had time to reach the disk.
     */
    void chunkUnloading(Chunk chunk) {
        HopperRecord record = loaded.get(ChunkId.of(chunk));
        if (record == null) {
            return;
        }
        if (record.statsDirty()) {
            try {
                record.statsWritten();
                store.write(chunk.getWorld().getBlockAt(record.x, record.y, record.z), record, false);
            } catch (RuntimeException refused) {
                logger.log(Level.FINE, "Could not write " + record + " while its chunk unloaded", refused);
            }
        }
        savedLater(record.id, record.saleSeq());
        loaded.remove(record.chunk, record);
        whenGone.accept(record);
    }

    /** The world is being saved: once the save is on disk, the sales it holds (and the hoppers gone from it) are forgotten. */
    void worldSaving(World world) {
        for (HopperRecord record : loaded.values()) {
            if (record.world.equals(world.getUID())) {
                savedLater(record.id, record.saleSeq());
            }
        }
        List<UUID> gone = new ArrayList<>();
        for (Map.Entry<UUID, UUID> each : goneSinceSave.entrySet()) {
            if (each.getValue().equals(world.getUID())) {
                gone.add(each.getKey());
            }
        }
        if (!gone.isEmpty()) {
            scheduler.runLater(() -> gone.forEach(hopper -> {
                if (goneSinceSave.remove(hopper) != null) {
                    journal.forget(hopper);
                }
            }), SAVED_AFTER_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void savedLater(UUID hopper, long seq) {
        if (!journal.entries(hopper).isEmpty()) {
            scheduler.runLater(() -> journal.saved(hopper, seq), SAVED_AFTER_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Forgets every loaded record of a world that is unloading. */
    void worldUnloading(World world) {
        List<HopperRecord> gone = new ArrayList<>();
        for (HopperRecord record : loaded.values()) {
            if (record.world.equals(world.getUID())) {
                gone.add(record);
            }
        }
        for (HopperRecord record : gone) {
            loaded.remove(record.chunk, record);
            whenGone.accept(record);
        }
    }

    /** Forgets every record (the plugin is stopping); counters are written by the caller first. */
    void clear() {
        loaded.clear();
    }

    Set<UUID> goneSinceSave() {
        return Set.copyOf(goneSinceSave.keySet());
    }

    static Registry.Entry entry(HopperRecord record, World world) {
        return new Registry.Entry(record.world, world.getName(), record.x, record.y, record.z, record.owner(), record.ownerName());
    }
}

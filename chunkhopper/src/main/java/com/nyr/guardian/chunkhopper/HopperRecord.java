package com.nyr.guardian.chunkhopper;

import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * A Chunk Hopper whose chunk is loaded: what its TileState holds, kept in memory for the drops that arrive every tick.
 *
 * <p>Only the thread that owns the hopper's chunk changes a record (on Folia that is the region's thread; elsewhere the
 * main thread): drops, sales and menu edits all run there. Other threads only read it, so every field is volatile and the
 * filter is an immutable value replaced in one write.</p>
 */
final class HopperRecord {

    final UUID world;
    final int x;
    final int y;
    final int z;
    final ChunkId chunk;
    /** This placed Chunk Hopper; recorded sales name it. */
    final UUID id;
    /** Whether the id was made just now (a hopper written before ids existed): it still has to be written. */
    final boolean newId;

    private volatile long saleSeq;
    private volatile UUID owner;
    private volatile String ownerName;
    private volatile Filter filter;
    private volatile boolean autoSell;
    private volatile long collected;
    private volatile double earned;
    /** Counters changed since they were last written to the TileState. */
    private volatile boolean statsDirty;
    /** When a full hopper that found nothing to sell may try again (System.nanoTime), so a busy farm does not price every drop. */
    private volatile long fullSaleRetryAt;

    HopperRecord(UUID world, int x, int y, int z, HopperStore.Data data) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.chunk = new ChunkId(world, x >> 4, z >> 4);
        this.newId = data.id() == null;
        this.id = newId ? UUID.randomUUID() : data.id();
        this.saleSeq = data.saleSeq();
        this.owner = data.owner();
        this.ownerName = data.ownerName();
        this.filter = data.filter();
        this.autoSell = data.autoSell();
        this.collected = data.collected();
        this.earned = data.earned();
        this.fullSaleRetryAt = System.nanoTime();
    }

    boolean at(Block block) {
        return block.getX() == x && block.getY() == y && block.getZ() == z && block.getWorld().getUID().equals(world);
    }

    boolean at(int bx, int by, int bz) {
        return bx == x && by == y && bz == z;
    }

    Location center(World in) {
        return new Location(in, x + 0.5, y + 0.5, z + 0.5);
    }

    UUID owner() {
        return owner;
    }

    String ownerName() {
        return ownerName;
    }

    boolean ownedBy(UUID player) {
        return owner != null && owner.equals(player);
    }

    Filter filter() {
        return filter;
    }

    void filter(Filter next) {
        this.filter = next;
    }

    boolean autoSell() {
        return autoSell;
    }

    void autoSell(boolean on) {
        this.autoSell = on;
    }

    long collected() {
        return collected;
    }

    double earned() {
        return earned;
    }

    void addCollected(long items) {
        collected += items;
        statsDirty = true;
    }

    void addEarned(double money) {
        earned += money;
        statsDirty = true;
    }

    boolean statsDirty() {
        return statsDirty;
    }

    void statsWritten() {
        statsDirty = false;
    }

    long saleSeq() {
        return saleSeq;
    }

    void saleSeq(long seq) {
        this.saleSeq = seq;
    }

    /** The next sale's number (on the hopper's own thread). */
    long nextSaleSeq() {
        saleSeq = saleSeq + 1;
        return saleSeq;
    }

    boolean mayTrySaleWhenFull(long now) {
        return now - fullSaleRetryAt >= 0;
    }

    void noSaleWhenFullUntil(long nanos) {
        fullSaleRetryAt = nanos;
    }

    HopperStore.Data data() {
        return new HopperStore.Data(id, owner, ownerName, filter, autoSell, collected, earned, saleSeq);
    }

    @Override
    public String toString() {
        return "ChunkHopper[" + x + "," + y + "," + z + " owner " + ownerName + "]";
    }
}

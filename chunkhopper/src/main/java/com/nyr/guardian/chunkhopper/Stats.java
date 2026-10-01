package com.nyr.guardian.chunkhopper;

import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;

/** What the plugin did since the server started, for /chunkhopper status. Counts survive /chunkhopper reload. */
final class Stats {

    final LongAdder collected = new LongAdder();
    final LongAdder notInFilter = new LongAdder();
    final LongAdder full = new LongAdder();
    final LongAdder droppedByPlayers = new LongAdder();
    final LongAdder deathDrops = new LongAdder();
    final LongAdder fishingCatches = new LongAdder();
    final LongAdder minedByDropEvent = new LongAdder();
    final LongAdder minedByBreakCell = new LongAdder();
    /** Items BlockDropItemEvent listed, and how many of them were already in the world (ItemSpawnEvent had fired first). */
    final LongAdder dropEventItems = new LongAdder();
    final LongAdder dropEventItemsAlreadySpawned = new LongAdder();
    /** Chunk Hoppers broken by players into a BlockDropItemEvent, and how many items those events listed. */
    final LongAdder hopperBreaks = new LongAdder();
    final LongAdder hopperBreakItems = new LongAdder();
    final LongAdder sold = new LongAdder();
    final DoubleAdder earned = new DoubleAdder();
    final LongAdder sales = new LongAdder();
    final LongAdder refusedSales = new LongAdder();
    /** Sales the saved world had lost after a crash (they may have been paid), and the items taken out again for them. */
    final LongAdder rolledBackSales = new LongAdder();
    final LongAdder rolledBackItems = new LongAdder();
    final LongAdder ghostsRemoved = new LongAdder();
    final LongAdder placed = new LongAdder();
    final LongAdder removed = new LongAdder();
    final LongAdder forgotten = new LongAdder();
    final LongAdder explosionsBlocked = new LongAdder();
}

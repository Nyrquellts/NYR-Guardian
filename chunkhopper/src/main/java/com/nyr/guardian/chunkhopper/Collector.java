package com.nyr.guardian.chunkhopper;

import com.tcoded.folialib.impl.PlatformScheduler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Takes each drop into its chunk's Chunk Hopper as the item spawns, so a collected item never exists on the ground.
 *
 * <p>What a player is getting or losing is never taken: items a player throws or drops, what /give hands out, a player's
 * death drops, a fishing catch, and (unless configured) the drops of a block a player breaks. ItemSpawnEvent runs on the
 * thread that owns the item's chunk, which owns the Chunk Hopper in it.</p>
 */
final class Collector implements Listener {

    /**
     * The pickup delay vanilla gives every item that leaves a player: thrown, dropped, handed out by /give (the copy /give
     * spawns gets its "never" delay only after it spawned) or lost on death. Block, mob and container drops get 10 or 0.
     */
    static final int DROPPED_BY_A_PLAYER = 40;

    /** How long a mark (a broken block's cell, a known item) waits for its item to spawn; spawns follow within the tick. */
    private static final long MARK_NANOS = 2_000_000_000L;

    private static final long FULL_SALE_RETRY_NANOS = 1_000_000_000L;

    private static final ThreadLocal<Boolean> OWN_DROPS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Stacks one death is about to drop in a chunk, removed as their items spawn. */
    private static final class Deaths {
        final List<ItemStack> stacks = new ArrayList<>();
        final long at = System.nanoTime();
    }

    private final Hoppers hoppers;
    private final Seller seller;
    private final Stats stats;
    private final PlatformScheduler scheduler;
    private final boolean collectPlayerMined;
    private final Map<ChunkId, Deaths> deaths = new ConcurrentHashMap<>();
    private final Map<BlockPos, Long> brokenCells = new ConcurrentHashMap<>();
    private final Map<UUID, Long> minedItems = new ConcurrentHashMap<>();
    private final Map<UUID, Long> fishedItems = new ConcurrentHashMap<>();

    Collector(Hoppers hoppers, Seller seller, Stats stats, PlatformScheduler scheduler, boolean collectPlayerMined) {
        this.hoppers = hoppers;
        this.seller = seller;
        this.stats = stats;
        this.scheduler = scheduler;
        this.collectPlayerMined = collectPlayerMined;
    }

    /** Runs {@code drop} (which spawns items) so that none of the items it spawns is collected: the plugin's own drops. */
    static void ownDrops(Runnable drop) {
        boolean before = OWN_DROPS.get();
        OWN_DROPS.set(Boolean.TRUE);
        try {
            drop.run();
        } finally {
            OWN_DROPS.set(before);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (OWN_DROPS.get()) {
            return;
        }
        Location at = event.getLocation();
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        HopperRecord record = hoppers.at(world, at.getBlockX(), at.getBlockZ());
        if (record == null) {
            return;
        }
        Item item = event.getEntity();
        ItemStack stack = item.getItemStack();
        if (stack.getType().isAir() || stack.getAmount() <= 0) {
            return;
        }
        if (item.getThrower() != null || item.getPickupDelay() >= DROPPED_BY_A_PLAYER) {
            stats.droppedByPlayers.increment();
            return;
        }
        UUID id = item.getUniqueId();
        if (fishedItems.remove(id) != null) {
            stats.fishingCatches.increment();
            return;
        }
        if (deathDrop(record.chunk, stack)) {
            stats.deathDrops.increment();
            return;
        }
        if (!collectPlayerMined) {
            if (minedItems.remove(id) != null) {
                stats.minedByDropEvent.increment();
                return;
            }
            Long broken = brokenCells.get(BlockPos.of(at));
            if (broken != null && System.nanoTime() - broken < MARK_NANOS) {
                stats.minedByBreakCell.increment();
                return;
            }
        }
        if (!record.filter().accepts(stack.getType())) {
            stats.notInFilter.increment();
            return;
        }
        Hopper hopper = hoppers.live(record, world);
        if (hopper == null) {
            return;
        }
        Inventory inventory = hopper.getInventory();
        int offered = stack.getAmount();
        ItemStack rest = offer(inventory, stack);
        if (rest != null && record.autoSell()) {
            long now = System.nanoTime();
            if (record.mayTrySaleWhenFull(now) && seller.canSell()) {
                if (seller.sell(record, inventory, world).items() > 0) {
                    rest = offer(inventory, rest);
                } else {
                    // Full of things without a price: try again in a second, not on every drop.
                    record.noSaleWhenFullUntil(now + FULL_SALE_RETRY_NANOS);
                }
            }
        }
        int taken = offered - (rest == null ? 0 : rest.getAmount());
        if (taken > 0) {
            record.addCollected(taken);
            stats.collected.add(taken);
        }
        if (rest == null) {
            event.setCancelled(true);
        } else {
            stats.full.increment();
            if (taken > 0) {
                item.setItemStack(rest);
            }
        }
    }

    /** Puts a copy of the stack into the inventory; returns what did not fit, or null when all of it did. */
    static ItemStack offer(Inventory inventory, ItemStack stack) {
        HashMap<Integer, ItemStack> left = inventory.addItem(stack.clone());
        if (left.isEmpty()) {
            return null;
        }
        int amount = 0;
        for (ItemStack part : left.values()) {
            amount += part.getAmount();
        }
        ItemStack rest = stack.clone();
        rest.setAmount(amount);
        return rest;
    }

    // ------------------------------------------------------------------ what players get or lose

    /** A player's death drops spawn right after this, in the chunk they died in: those stacks are not collected. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getDrops().isEmpty()) {
            return;
        }
        Player player = event.getEntity();
        Location at = player.getLocation();
        ChunkId chunk = ChunkId.of(at.getWorld(), at.getBlockX(), at.getBlockZ());
        if (hoppers.in(chunk) == null) {
            return;
        }
        Deaths pending = deaths.compute(chunk, (key, existing) -> existing != null && System.nanoTime() - existing.at < MARK_NANOS ? existing : new Deaths());
        synchronized (pending) {
            for (ItemStack drop : event.getDrops()) {
                if (drop != null && !drop.getType().isAir()) {
                    pending.stacks.add(drop.clone());
                }
            }
        }
        scheduler.runAtLocation(at, ignored -> deaths.remove(chunk, pending));
    }

    private boolean deathDrop(ChunkId chunk, ItemStack stack) {
        Deaths pending = deaths.get(chunk);
        if (pending == null) {
            return false;
        }
        if (System.nanoTime() - pending.at >= MARK_NANOS) {
            deaths.remove(chunk, pending);
            return false;
        }
        synchronized (pending) {
            Iterator<ItemStack> each = pending.stacks.iterator();
            while (each.hasNext()) {
                if (each.next().equals(stack)) {
                    each.remove();
                    return true;
                }
            }
        }
        return false;
    }

    /** A fishing catch flies to the player who caught it; the event fires before the item spawns. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH && event.getCaught() instanceof Item caught) {
            fishedItems.put(caught.getUniqueId(), System.nanoTime());
        }
    }

    /** A player broke a block: whatever spawns in its cell during this tick is what they broke. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (collectPlayerMined) {
            return;
        }
        Block block = event.getBlock();
        if (hoppers.at(block.getWorld(), block.getX(), block.getZ()) == null) {
            return;
        }
        BlockPos cell = BlockPos.of(block);
        brokenCells.put(cell, System.nanoTime());
        scheduler.runAtLocation(block.getLocation(), ignored -> brokenCells.remove(cell));
    }

    /** The items a player's block break drops, before they spawn. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockDrops(BlockDropItemEvent event) {
        Block block = event.getBlock();
        if (hoppers.at(block.getWorld(), block.getX(), block.getZ()) == null) {
            return;
        }
        long now = System.nanoTime();
        for (Item item : event.getItems()) {
            stats.dropEventItems.increment();
            if (item.isValid()) {
                stats.dropEventItemsAlreadySpawned.increment();
            }
            if (!collectPlayerMined) {
                minedItems.put(item.getUniqueId(), now);
            }
        }
    }

    /** Drops marks whose items never spawned (another plugin cancelled them). Called now and then from any thread. */
    void purge() {
        long now = System.nanoTime();
        brokenCells.values().removeIf(at -> now - at >= MARK_NANOS);
        minedItems.values().removeIf(at -> now - at >= MARK_NANOS);
        fishedItems.values().removeIf(at -> now - at >= MARK_NANOS);
        deaths.values().removeIf(pending -> now - pending.at >= MARK_NANOS);
    }
}

package com.nyr.guardian.chunkhopper;

import com.nyr.guardian.common.Alerts;
import com.nyr.guardian.common.Messages;
import com.tcoded.folialib.impl.PlatformScheduler;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * Sells what Chunk Hoppers hold, so that a crash can lose money but never pay twice:
 *
 * <ol>
 *   <li>the priced stacks leave the hopper and its sale number rises, in one TileState write (the world saves both or
 *   neither);</li>
 *   <li>the sale is recorded in the sale journal and forced to disk;</li>
 *   <li>only then is the owner paid. If the economy refuses, the sale is marked void and the stacks go back into the
 *   hopper (or onto the ground beside it when it filled up).</li>
 * </ol>
 *
 * <p>After a crash the world is as it was last saved; a Chunk Hopper whose saved sale number is below a recorded sale
 * gets that sale's stacks taken out again as its chunk loads ({@link Hoppers#discover}).</p>
 */
final class Seller {

    /** One sale's outcome as the seller sees it at once: the stacks left the hopper; payment follows once recorded. */
    record Sale(int items, double amount, String refused) {
        static final Sale NOTHING = new Sale(0, 0, null);
    }

    /** What an owner earned since they were last told. */
    private static final class Notice {
        double amount;
        long items;
    }

    /** A recorded sale waiting to be paid (or refused); {@code slots} are where its stacks came from. */
    private record Payment(HopperRecord record, SaleJournal.Entry entry, World world, List<Integer> slots, int attempt) {
        String key() {
            return entry.hopper() + "#" + entry.seq();
        }
    }

    private static final int MAX_ATTEMPTS_UNLOADED = 30;

    private final Server server;
    private final PlatformScheduler scheduler;
    private final Logger logger;
    private final Hoppers hoppers;
    private final SaleJournal journal;
    private final Prices prices;
    private final Payout payout;
    private final Stats stats;
    private final Messages messages;
    private final Alerts alerts;
    private final long intervalTicks;
    private final boolean notifyOwner;
    private final Map<UUID, Notice> notices = new ConcurrentHashMap<>();
    /** Sales recorded (or being recorded) and not yet paid, by hopper#seq. */
    private final Map<String, Payment> unpaid = new ConcurrentHashMap<>();
    private volatile Runnable everyInterval = () -> { };
    private volatile boolean running;

    Seller(Server server, PlatformScheduler scheduler, Logger logger, Hoppers hoppers, SaleJournal journal, Prices prices, Payout payout,
           Stats stats, Messages messages, Alerts alerts, int intervalSeconds, boolean notifyOwner) {
        this.server = server;
        this.scheduler = scheduler;
        this.logger = logger;
        this.hoppers = hoppers;
        this.journal = journal;
        this.prices = prices;
        this.payout = payout;
        this.stats = stats;
        this.messages = messages;
        this.alerts = alerts;
        this.intervalTicks = Math.max(1, intervalSeconds) * 20L;
        this.notifyOwner = notifyOwner;
    }

    /** Also runs on every interval, on the timer's thread (the plugin uses it to purge stale marks). */
    void everyInterval(Runnable task) {
        this.everyInterval = task;
    }

    void start() {
        running = true;
        scheduler.runTimer(this::tick, intervalTicks, intervalTicks);
    }

    /**
     * Stops selling. Sales already recorded are paid now, on this thread: the scheduled payments would be cancelled with
     * the plugin's tasks, and a recorded sale must not stay unpaid when the world saves its removal.
     */
    void stop() {
        running = false;
        journal.flush();
        for (Payment payment : new ArrayList<>(unpaid.values())) {
            try {
                settle(payment, true);
            } catch (RuntimeException failed) {
                logger.log(Level.WARNING, "Could not pay a Chunk Hopper sale while stopping: " + failed, failed);
            }
        }
    }

    long intervalSeconds() {
        return intervalTicks / 20;
    }

    Payout payout() {
        return payout;
    }

    Prices prices() {
        return prices;
    }

    int unpaid() {
        return unpaid.size();
    }

    /** Whether selling can happen now: an economy is there and the sale journal can be written. */
    boolean canSell() {
        return payout.ready() && !journal.broken();
    }

    /** The timer: every loaded Chunk Hopper that sells, or has counters to write, gets a turn on its own thread. */
    private void tick() {
        if (!running) {
            return;
        }
        for (HopperRecord record : hoppers.all()) {
            if (record.autoSell() || record.statsDirty()) {
                hoppers.runAt(record, () -> maintain(record));
            }
        }
        tellOwners();
        everyInterval.run();
    }

    /** One hopper's turn, on its own thread: sell what it holds, then write its counters. */
    void maintain(HopperRecord record) {
        if (!running || !hoppers.current(record)) {
            return;
        }
        World world = hoppers.world(record);
        if (world == null || !world.isChunkLoaded(record.chunk.x(), record.chunk.z())) {
            return;
        }
        if (record.autoSell() && canSell()) {
            Hopper hopper = hoppers.live(record, world);
            if (hopper == null) {
                return;
            }
            sell(record, hopper.getInventory(), world);
        }
        hoppers.flush(record);
    }

    /**
     * Sells every priced stack in the hopper's live inventory, on the hopper's thread: takes the stacks out, writes the
     * hopper's new sale number with the removal, records the sale, and pays the owner once the record is on disk.
     */
    Sale sell(HopperRecord record, Inventory inventory, World world) {
        if (record.owner() == null || !canSell()) {
            return Sale.NOTHING;
        }
        List<Integer> slots = new ArrayList<>();
        List<ItemStack> taken = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int items = 0;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || stack.getType().isAir() || stack.getAmount() <= 0) {
                continue;
            }
            BigDecimal unit = prices.unit(stack);
            if (unit == null) {
                continue;
            }
            inventory.setItem(slot, null);
            slots.add(slot);
            taken.add(stack);
            total = total.add(unit.multiply(BigDecimal.valueOf(stack.getAmount())));
            items += stack.getAmount();
        }
        if (taken.isEmpty()) {
            return Sale.NOTHING;
        }
        // Money is never rounded up: a sale pays at most what the items are worth.
        double amount = total.setScale(2, RoundingMode.DOWN).doubleValue();
        if (amount <= 0) {
            putBack(record, inventory, world, slots, taken);
            return Sale.NOTHING;
        }
        long seq = record.nextSaleSeq();
        // The removal and the sale number reach the world in one write: saved together or not at all.
        hoppers.save(record, world);
        SaleJournal.Entry entry = new SaleJournal.Entry(record.id, seq, world.getUID(), record.x, record.y, record.z, record.owner(), amount,
            List.copyOf(taken), false);
        Payment payment = new Payment(record, entry, world, List.copyOf(slots), 0);
        unpaid.put(payment.key(), payment);
        Location at = record.center(world);
        journal.sale(entry).whenComplete((recorded, failure) -> {
            if (failure != null) {
                scheduler.runAtLocation(at, ignored -> refuse(payment, "the sale journal could not be written", true));
            } else {
                scheduler.runAtLocation(at, ignored -> settle(payment, false));
            }
        });
        return new Sale(items, amount, null);
    }

    /** Pays a recorded sale (on the hopper's thread, or on the stopping thread with {@code stopping}). */
    private void settle(Payment payment, boolean stopping) {
        if (unpaid.remove(payment.key()) == null) {
            return; // already settled (the plugin stopped and paid it)
        }
        SaleJournal.Entry entry = payment.entry();
        String refused = payout.deposit(entry.owner(), entry.amount());
        if (refused == null) {
            paid(payment);
            return;
        }
        unpaid.put(payment.key(), payment);
        refuse(payment, refused, stopping);
    }

    private void paid(Payment payment) {
        SaleJournal.Entry entry = payment.entry();
        HopperRecord current = hoppers.in(payment.record().chunk);
        if (current != null && current.id.equals(entry.hopper())) {
            current.addEarned(entry.amount());
        }
        stats.sales.increment();
        stats.sold.add(entry.items());
        stats.earned.add(entry.amount());
        if (notifyOwner) {
            Notice notice = notices.computeIfAbsent(entry.owner(), ignored -> new Notice());
            synchronized (notice) {
                notice.amount += entry.amount();
                notice.items += entry.items();
            }
        }
    }

    /**
     * The economy refused (or the sale could not be recorded): the sale is void and its stacks go back into the hopper.
     * When the hopper's chunk is not loaded they cannot go back yet: the payment is tried again every interval instead,
     * and the stacks go back on the first try that finds the chunk loaded and the economy still refusing.
     */
    private void refuse(Payment payment, String reason, boolean stopping) {
        SaleJournal.Entry entry = payment.entry();
        World world = payment.world();
        boolean loadedHere = world.isChunkLoaded(entry.x() >> 4, entry.z() >> 4)
            && (stopping || scheduler.isOwnedByCurrentRegion(new Location(world, entry.x(), entry.y(), entry.z())));
        HopperRecord record = hoppers.in(payment.record().chunk);
        Hopper hopper = loadedHere && record != null && record.id.equals(entry.hopper()) ? hoppers.live(record, world) : null;
        if (hopper == null && !stopping && payment.attempt() < MAX_ATTEMPTS_UNLOADED) {
            Payment again = new Payment(payment.record(), entry, world, payment.slots(), payment.attempt() + 1);
            unpaid.put(again.key(), again);
            scheduler.runLater(() -> scheduler.runAtLocation(new Location(world, entry.x(), entry.y(), entry.z()), ignored -> settle(again, false)),
                intervalTicks);
            return;
        }
        unpaid.remove(payment.key());
        journal.voided(entry.hopper(), entry.seq());
        stats.refusedSales.increment();
        if (hopper != null) {
            putBack(record, hopper.getInventory(), world, payment.slots(), entry.stacks());
        } else {
            logger.warning("A refused Chunk Hopper sale could not go back into its hopper (its chunk is not loaded): "
                + entry.items() + " item(s) at " + world.getName() + " " + entry.x() + " " + entry.y() + " " + entry.z() + " are lost.");
        }
        alerts.send("deposit-failed:" + entry.owner(), messages.format("alert-deposit-failed", "owner", ownerName(payment),
            "amount", payout.format(entry.amount()), "world", world.getName(), "x", entry.x(), "y", entry.y(), "z", entry.z(), "reason", reason));
    }

    private static String ownerName(Payment payment) {
        String name = payment.record().ownerName();
        return name == null ? String.valueOf(payment.entry().owner()) : name;
    }

    /** The unsold stacks go back to the slots they came from, else anywhere in the hopper, else onto the ground beside it. */
    private static void putBack(HopperRecord record, Inventory inventory, World world, List<Integer> slots, List<ItemStack> taken) {
        List<ItemStack> homeless = new ArrayList<>();
        for (int i = 0; i < taken.size(); i++) {
            if (i < slots.size()) {
                int slot = slots.get(i);
                ItemStack there = inventory.getItem(slot);
                if (there == null || there.getType().isAir()) {
                    inventory.setItem(slot, taken.get(i).clone());
                    continue;
                }
            }
            homeless.add(taken.get(i).clone());
        }
        if (homeless.isEmpty()) {
            return;
        }
        Map<Integer, ItemStack> left = inventory.addItem(homeless.toArray(new ItemStack[0]));
        if (left.isEmpty()) {
            return;
        }
        Location beside = new Location(world, record.x + 0.5, record.y + 1.2, record.z + 0.5);
        Collector.ownDrops(() -> {
            for (ItemStack stack : left.values()) {
                world.dropItemNaturally(beside, stack);
            }
        });
    }

    /**
     * Tells each online owner what their Chunk Hoppers sold since the last message, in one line. The interval timer is the
     * only caller, so an owner hears at most once per interval however many hoppers they have.
     */
    void tellOwners() {
        for (Map.Entry<UUID, Notice> entry : notices.entrySet()) {
            Notice notice = entry.getValue();
            double amount;
            long items;
            synchronized (notice) {
                if (notice.items == 0) {
                    continue;
                }
                amount = notice.amount;
                items = notice.items;
                notice.amount = 0;
                notice.items = 0;
            }
            Player owner = server.getPlayer(entry.getKey());
            if (owner != null) {
                String line = messages.format("sold", "amount", payout.format(amount), "items", items);
                if (!line.isEmpty()) {
                    scheduler.runAtEntity(owner, ignored -> owner.sendMessage(line));
                }
            }
        }
    }
}

package com.nyr.guardian.smarttick;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;

/**
 * One run of SmartTick between a start and a stop (a reload makes a new one). Every tick, on the global thread, it reads the
 * load and judges a slice of the loaded villagers, so a whole scan is spread over {@code scan-seconds}; each villager is
 * judged on its own thread (on Folia its region's), and at most {@code batch-per-second} villagers fall asleep or wake per
 * second.
 */
final class Engine {

    /** What the villagers follow: the load, /smarttick sleep, or /smarttick wakeall. */
    enum Mode {
        AUTO, SLEEP, AWAKE
    }

    /** Counts of one scan over the loaded villagers; scans are numbered from 1. */
    static final class Pass {
        final AtomicInteger seen = new AtomicInteger();
        final AtomicInteger eligible = new AtomicInteger();
        final AtomicInteger asleep = new AtomicInteger();
        final AtomicInteger others = new AtomicInteger();
        final AtomicInteger behind = new AtomicInteger();
        final long number;
        volatile boolean complete;

        Pass(long number) {
            this.number = number;
        }
    }

    /** What SmartTick did since the server started; kept across reloads. */
    static final class Totals {
        final LongAdder slept = new LongAdder();
        final LongAdder woken = new LongAdder();
        final LongAdder restocked = new LongAdder();
        final LongAdder wokenOnLoad = new LongAdder();
        final LongAdder keptOnLoad = new LongAdder();
        final LongAdder wokenOnUnload = new LongAdder();
    }

    /** After a trade (or its job site breaking) a villager stays awake this long, so its brain can finish what it does then. */
    static final long AWAKE_AFTER_TRADE_MILLIS = 10_000;
    private static final long MILLI_TOKEN = 1000;

    private final SmartTickPlugin plugin;
    private final Settings settings;
    private final Load load;
    private final Hysteresis server;
    private final Tracker tracker;
    private final Totals totals;
    private final Eligibility eligibility;
    private final Restocker restocker;
    private final LongSupplier clock;
    private final boolean inline;

    private volatile boolean active = true;
    private volatile Mode mode = Mode.AUTO;
    private volatile boolean restartRequested;
    private volatile CommandSender toldWhenAwake;
    private volatile long awakeAfterPass;
    private volatile Load.Reading lastReading;

    private final AtomicLong tokens = new AtomicLong();
    private final long refillPerTick;
    private final long tokenCap;

    // Scan state, touched only by the global thread. Scans are timed in ticks: a lagging server scans no faster per tick.
    private List<Villager> snapshot = List.of();
    private int cursor;
    private long ticks;
    private long nextPassTick;
    private int perTick = 1;
    private volatile Pass current;
    private volatile Pass last;
    private volatile Pass settled;
    private boolean regionsAlerted;

    /**
     * {@code server} is the whole server's state for a global load (kept across reloads); {@code inline} judges villagers
     * right in the tick, where every entity lives on the main thread; {@code clock} counts milliseconds.
     */
    Engine(SmartTickPlugin plugin, Settings settings, Load load, Hysteresis server, Tracker tracker, Totals totals,
           Eligibility eligibility, Restocker restocker, LongSupplier clock, boolean inline) {
        this.plugin = plugin;
        this.settings = settings;
        this.load = load;
        this.server = server;
        this.tracker = tracker;
        this.totals = totals;
        this.eligibility = eligibility;
        this.restocker = restocker;
        this.clock = clock;
        this.inline = inline;
        this.refillPerTick = Math.max(1, settings.batchPerSecond() * MILLI_TOKEN / 20);
        this.tokenCap = Math.max(MILLI_TOKEN, refillPerTick);
        this.current = new Pass(0);
    }

    /** Runs every tick on the global thread (the main thread outside Folia). */
    void tick() {
        if (!active) {
            return;
        }
        load.tick();
        tokens.getAndUpdate(t -> Math.min(tokenCap, t + refillPerTick));
        long now = clock.getAsLong();
        if (load.global()) {
            Load.Reading reading = load.read(null);
            lastReading = reading;
            // the state follows the load in every mode; staff hear about it only while villagers follow it (auto)
            if (server.update(settings.classify(reading), now, settings.holdMillis()) && mode == Mode.AUTO) {
                plugin.alertLoad(server.behind(), reading == null ? "?" : reading.display());
            }
        }
        ticks++;
        if (restartRequested || (cursor >= snapshot.size() && ticks >= nextPassTick)) {
            startPass(now);
        }
        int end = Math.min(snapshot.size(), cursor + perTick);
        Pass pass = current;
        while (cursor < end) {
            Villager villager = snapshot.get(cursor++);
            if (inline) {
                judgeSafely(villager, pass);
            } else {
                plugin.scheduler().runAtEntity(villager, task -> judgeSafely(villager, pass));
            }
        }
        if (cursor >= snapshot.size()) {
            pass.complete = true;
        }
    }

    private void startPass(long now) {
        restartRequested = false;
        Pass finished = current;
        if (finished.complete) {
            // Judged inline, a finished scan's counts are final at once. Judged on region threads (Folia), the last tasks may
            // still run, so the scan before it is the one that counts.
            settled = inline ? finished : last;
            last = finished;
        }
        current = new Pass(finished.number + 1);
        List<Villager> villagers = new ArrayList<>();
        for (World world : plugin.getServer().getWorlds()) {
            villagers.addAll(world.getEntitiesByClass(Villager.class));
        }
        snapshot = villagers;
        cursor = 0;
        long scanTicks = Math.max(1, settings.scanMillis() / 50);
        nextPassTick = ticks + scanTicks;
        perTick = (int) Math.max(1, (villagers.size() + scanTicks - 1) / scanTicks);
        tracker.forgetUnseen(now - Math.max(3 * settings.scanMillis(), 60_000));
        afterPass();
    }

    /** Alerts about regions falling behind (Folia) and tells whoever ran /smarttick wakeall when every villager is awake. */
    private void afterPass() {
        Pass done = settled;
        if (done == null) {
            return;
        }
        if (!load.global() && mode == Mode.AUTO) {
            int behind = done.behind.get();
            if (behind > 0 && !regionsAlerted) {
                regionsAlerted = true;
                plugin.alertRegions(true, behind);
            } else if (behind == 0 && regionsAlerted) {
                regionsAlerted = false;
                plugin.alertRegions(false, 0);
            }
        }
        CommandSender waiting = toldWhenAwake;
        if (mode == Mode.AWAKE && waiting != null && done.number > awakeAfterPass && done.asleep.get() == 0) {
            toldWhenAwake = null;
            plugin.tellAllAwake(waiting);
        }
    }

    private void judgeSafely(Villager villager, Pass pass) {
        try {
            judge(villager, pass);
        } catch (RuntimeException broken) {
            plugin.problem("judging a villager", broken);
        }
    }

    /** Decides one villager on its own thread: sleep it, wake it, restock it, or leave it. */
    void judge(Villager villager, Pass pass) {
        if (!active || !villager.isValid()) {
            return;
        }
        pass.seen.incrementAndGet();
        boolean asleep = Marks.asleep(villager);
        if (!asleep && !villager.isAware()) {
            pass.others.incrementAndGet();
            return;
        }
        if (asleep && villager.isAware()) {
            Marks.forget(villager);
            asleep = false;
        }
        long now = clock.getAsLong();
        Location at = villager.getLocation();
        Tracker.Tracked tracked = tracker.get(villager, now, asleep);
        long stillFor = tracked.observe(at, now);
        Eligibility.Verdict verdict = eligibility.check(villager, stillFor, tracked.awakeUntil, now);
        boolean behind = behind(tracked, at, now);
        if (behind) {
            pass.behind.incrementAndGet();
        }
        if (verdict.eligible()) {
            pass.eligible.incrementAndGet();
        }
        boolean wanted = switch (mode) {
            case AUTO -> verdict.eligible() && behind;
            case SLEEP -> verdict.eligible();
            case AWAKE -> false;
        };
        if (wanted && !asleep) {
            if (takeToken()) {
                Marks.sleep(villager);
                totals.slept.increment();
                asleep = true;
            }
        } else if (!wanted && asleep && takeToken()) {
            Marks.wake(villager);
            totals.woken.increment();
            asleep = false;
        }
        if (asleep) {
            pass.asleep.incrementAndGet();
            if (settings.restockWhileAsleep() && restocker.maybeRestock(villager, verdict.jobSite())) {
                totals.restocked.increment();
            }
        }
    }

    /** Whether the load where the villager stands counts as behind: the server's state, or on Folia its region's. */
    private boolean behind(Tracker.Tracked tracked, Location at, long now) {
        if (load.global()) {
            return server.behind();
        }
        Settings.Pressure pressure = settings.classify(load.read(at));
        Hysteresis region = tracked.region(pressure);
        region.update(pressure, now, settings.holdMillis());
        return region.behind();
    }

    private boolean takeToken() {
        return tokens.getAndUpdate(t -> t >= MILLI_TOKEN ? t - MILLI_TOKEN : t) >= MILLI_TOKEN;
    }

    /**
     * A chunk loaded with this villager asleep (saved asleep by a hard stop, or by Folia, which cannot wake villagers while it
     * stops): true to keep it asleep because it may sleep and the load still says so, false to wake it now.
     */
    boolean keepAsleepOnLoad(Villager villager) {
        if (!active) {
            return false;
        }
        long now = clock.getAsLong();
        Location at = villager.getLocation();
        Tracker.Tracked tracked = tracker.get(villager, now, true);
        long stillFor = tracked.observe(at, now);
        if (!eligibility.check(villager, stillFor, tracked.awakeUntil, now).eligible()) {
            return false;
        }
        return switch (mode) {
            case SLEEP -> true;
            case AWAKE -> false;
            case AUTO -> behind(tracked, at, now);
        };
    }

    /** A player opened or closed this villager's trades: it wakes now and stays awake a little while after. */
    void traded(Villager villager) {
        if (!active) {
            return;
        }
        long now = clock.getAsLong();
        tracker.holdAwake(villager, now, now + AWAKE_AFTER_TRADE_MILLIS);
        if (Marks.asleep(villager)) {
            Marks.wake(villager);
            totals.woken.increment();
        }
    }

    /**
     * A job site block broke: villagers asleep next to it and linked to it wake now, so their brains let go of it as the
     * game does (a librarian without trades can then take a new lectern and new trades).
     */
    void jobSiteBroken(Block block) {
        if (!active) {
            return;
        }
        long now = clock.getAsLong();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (Entity nearby : block.getWorld().getNearbyEntities(center, 2.5, 2.5, 2.5)) {
            if (!(nearby instanceof Villager villager) || !Marks.asleep(villager)) {
                continue;
            }
            Location jobSite = villager.getMemory(MemoryKey.JOB_SITE);
            if (jobSite != null && jobSite.getBlockX() == block.getX() && jobSite.getBlockY() == block.getY()
                && jobSite.getBlockZ() == block.getZ()) {
                tracker.holdAwake(villager, now, now + AWAKE_AFTER_TRADE_MILLIS);
                Marks.wake(villager);
                totals.woken.increment();
            }
        }
    }

    void sleepEveryone() {
        mode = Mode.SLEEP;
        toldWhenAwake = null;
        restartRequested = true;
    }

    void wakeEveryone(CommandSender tell) {
        mode = Mode.AWAKE;
        awakeAfterPass = current.number;
        toldWhenAwake = tell;
        restartRequested = true;
    }

    void followLoad() {
        mode = Mode.AUTO;
        toldWhenAwake = null;
        restartRequested = true;
    }

    void stop() {
        active = false;
    }

    Mode mode() {
        return mode;
    }

    /** The last global reading; null on Folia or while there is none. */
    Load.Reading lastReading() {
        return lastReading;
    }

    /** The most recent scan whose counts are final, or null before the first one. */
    Pass settled() {
        return settled;
    }

    Hysteresis server() {
        return server;
    }

    Load load() {
        return load;
    }

    Settings settings() {
        return settings;
    }

    Eligibility eligibility() {
        return eligibility;
    }

    Tracker tracker() {
        return tracker;
    }

    Restocker restocker() {
        return restocker;
    }

    long now() {
        return clock.getAsLong();
    }
}

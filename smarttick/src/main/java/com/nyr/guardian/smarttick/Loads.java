package com.nyr.guardian.smarttick;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Server;

/**
 * The load signal each server type offers, chosen once at start. Paper's tick time and Folia's region readings are not in
 * Spigot's API, so they are reached through method handles and every server links the same jar.
 */
final class Loads {

    /** Paper, Purpur and Folia: {@code Server#getAverageTickTime()}; on Folia it reads the calling thread's region. */
    private static final MethodHandle AVERAGE_TICK_TIME = find("getAverageTickTime", MethodType.methodType(double.class));
    /** Folia only: {@code Server#getRegionTPS(Location)}, the 5 s, 15 s, 1 m, 5 m and 15 m TPS of the region there. */
    private static final MethodHandle REGION_TPS = find("getRegionTPS", MethodType.methodType(double[].class, Location.class));

    private Loads() {
    }

    private static MethodHandle find(String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findVirtual(Server.class, name, type);
        } catch (NoSuchMethodException | IllegalAccessException absent) {
            return null;
        }
    }

    /** The best signal this server has: region readings on Folia, the tick time on Paper, a measured TPS elsewhere. */
    static Load pick(Server server, boolean folia, Logger logger) {
        if (folia) {
            return new RegionLoad(server, logger);
        }
        if (AVERAGE_TICK_TIME != null && serverMspt(server) >= 0) {
            return new ServerMspt(server);
        }
        return new MeasuredTps(System::nanoTime);
    }

    /** The server's own 5 s average tick time, or -1 where it cannot be read (Spigot, test servers). */
    static double serverMspt(Server server) {
        if (AVERAGE_TICK_TIME == null) {
            return -1;
        }
        try {
            double millis = (double) AVERAGE_TICK_TIME.invokeExact(server);
            return Double.isNaN(millis) || millis < 0 ? -1 : millis;
        } catch (Throwable unsupported) {
            return -1;
        }
    }

    /** Paper and Purpur: milliseconds per tick, the server's 5 s (100 tick) average. */
    static final class ServerMspt implements Load {

        private final Server server;

        ServerMspt(Server server) {
            this.server = server;
        }

        @Override
        public boolean global() {
            return true;
        }

        @Override
        public Reading read(Location where) {
            double millis = serverMspt(server);
            return millis < 0 ? null : new Reading(millis, Unit.MSPT);
        }

        @Override
        public String source() {
            return "the server's tick time, 5 s average";
        }
    }

    /**
     * Spigot has no tick-time API: ticks are counted by a task that runs every tick, over the last five seconds. A server
     * keeping up counts 100; a stalled one counts few or none, so a stall reads as low TPS rather than as no reading.
     */
    static final class MeasuredTps implements Load {

        private static final long WINDOW_NANOS = 5_000_000_000L;
        private final java.util.function.LongSupplier clock;
        private final long[] ticks = new long[512];
        private int newest = -1;
        private int count;
        private long first = Long.MIN_VALUE;

        MeasuredTps(java.util.function.LongSupplier nanoClock) {
            this.clock = nanoClock;
        }

        @Override
        public synchronized void tick() {
            long now = clock.getAsLong();
            if (first == Long.MIN_VALUE) {
                first = now;
            }
            newest = (newest + 1) % ticks.length;
            ticks[newest] = now;
            count = Math.min(count + 1, ticks.length);
        }

        /** Forgets every tick: after the plugin was off, the gap is not lag. */
        @Override
        public synchronized void restart() {
            newest = -1;
            count = 0;
            first = Long.MIN_VALUE;
        }

        @Override
        public synchronized Reading read(Location where) {
            if (count < 2) {
                return null;
            }
            long now = clock.getAsLong();
            long since = now - first;
            if (since <= 0) {
                return null;
            }
            if (since < WINDOW_NANOS) {
                // Fewer than five seconds counted yet: the rate between the first tick and now.
                return new Reading(Math.min(20.0, (count - 1) / (since / 1e9)), Unit.TPS);
            }
            int inWindow = 0;
            for (int i = 0; i < count; i++) {
                if (now - ticks[Math.floorMod(newest - i, ticks.length)] > WINDOW_NANOS) {
                    break;
                }
                inWindow++;
            }
            return new Reading(Math.min(20.0, inWindow / (WINDOW_NANOS / 1e9)), Unit.TPS);
        }

        @Override
        public boolean global() {
            return true;
        }

        @Override
        public String source() {
            return "ticks counted by SmartTick over 5 s (this server reports no tick time)";
        }
    }

    /**
     * Folia: every region ticks on its own, so each villager is judged by the region it stands in. Read on a region's thread,
     * {@code getAverageTickTime()} is that region's 5 s average tick time; if this server refuses it, the region's 5 s TPS
     * from {@code getRegionTPS} is used instead.
     */
    static final class RegionLoad implements Load {

        private final Server server;
        private final Logger logger;
        private volatile boolean tickTime = AVERAGE_TICK_TIME != null;

        RegionLoad(Server server, Logger logger) {
            this.server = server;
            this.logger = logger;
        }

        @Override
        public boolean global() {
            return false;
        }

        @Override
        public Reading read(Location where) {
            if (tickTime) {
                try {
                    double millis = (double) AVERAGE_TICK_TIME.invokeExact(server);
                    if (!Double.isNaN(millis) && millis >= 0) {
                        return new Reading(millis, Unit.MSPT);
                    }
                } catch (Throwable refused) {
                    tickTime = false;
                    logger.info("This server does not report region tick times (" + refused.getClass().getSimpleName()
                        + "); each region's TPS is used instead.");
                }
            }
            if (REGION_TPS != null && where != null && where.getWorld() != null) {
                try {
                    double[] tps = (double[]) REGION_TPS.invokeExact(server, where);
                    if (tps != null && tps.length > 0 && !Double.isNaN(tps[0])) {
                        return new Reading(tps[0], Unit.TPS);
                    }
                } catch (Throwable unreadable) {
                    return null;
                }
            }
            return null;
        }

        @Override
        public String source() {
            return tickTime ? "each region's own tick time, 5 s average" : "each region's own TPS, 5 s average";
        }
    }
}

package com.nyr.guardian.smarttick;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Villager;

/**
 * What SmartTick remembers about each loaded villager between scans: where it has stood still since when, until when it
 * must stay awake (after a trade), and on Folia whether its region counts as behind. Kept only in memory; entries of
 * villagers not seen for a while are dropped.
 */
final class Tracker {

    /** A villager counts as standing still while it stays within this distance of one spot. */
    static final double STILL_RADIUS = 1.5;

    static final class Tracked {
        private volatile UUID world;
        private volatile double x;
        private volatile double y;
        private volatile double z;
        private volatile long stillSince;
        volatile long lastSeen;
        volatile long awakeUntil;
        private final boolean foundAsleep;
        private volatile Hysteresis region;

        Tracked(Location at, long now, boolean asleep) {
            this.foundAsleep = asleep;
            moveTo(at, now);
            this.lastSeen = now;
        }

        /**
         * Folia: this villager's region, judged by the readings this villager saw. A villager found asleep (loaded that way,
         * or asleep before a restart) starts behind unless its region already reads caught up, so it wakes at once in a region
         * keeping up and is not woken just to fall asleep again in one still behind.
         */
        Hysteresis region(Settings.Pressure first) {
            Hysteresis current = region;
            if (current == null) {
                current = new Hysteresis(foundAsleep && first != Settings.Pressure.LOW);
                region = current;
            }
            return current;
        }

        private void moveTo(Location at, long now) {
            World w = at.getWorld();
            this.world = w == null ? null : w.getUID();
            this.x = at.getX();
            this.y = at.getY();
            this.z = at.getZ();
            this.stillSince = now;
        }

        /** Records where the villager stands now; returns how long it has stood within {@link #STILL_RADIUS} of one spot. */
        long observe(Location at, long now) {
            lastSeen = now;
            World w = at.getWorld();
            double dx = at.getX() - x;
            double dy = at.getY() - y;
            double dz = at.getZ() - z;
            if (w == null || !w.getUID().equals(world) || dx * dx + dy * dy + dz * dz > STILL_RADIUS * STILL_RADIUS) {
                moveTo(at, now);
            }
            return now - stillSince;
        }

        /** How long it has stood still, as of the last observation. */
        long stillFor(long now) {
            return now - stillSince;
        }
    }

    private final Map<UUID, Tracked> villagers = new ConcurrentHashMap<>();

    /** The entry for a villager, created on first sight; {@code asleep} says whether it carries SmartTick's mark. */
    Tracked get(Villager villager, long now, boolean asleep) {
        return villagers.computeIfAbsent(villager.getUniqueId(), id -> new Tracked(villager.getLocation(), now, asleep));
    }

    /** The entry if there is one, without creating it. */
    Tracked peek(UUID villager) {
        return villagers.get(villager);
    }

    /** Keeps a villager awake until {@code until}, e.g. while and just after a player trades with it. */
    void holdAwake(Villager villager, long now, long until) {
        Tracked tracked = get(villager, now, false);
        tracked.awakeUntil = Math.max(tracked.awakeUntil, until);
    }

    /** Forgets every region's past relapses, as a reload does for the server's. */
    void forgetRelapses() {
        for (Tracked tracked : villagers.values()) {
            Hysteresis region = tracked.region;
            if (region != null) {
                region.forgetRelapses();
            }
        }
    }

    /** Drops entries not seen since {@code before}. */
    void forgetUnseen(long before) {
        villagers.values().removeIf(tracked -> tracked.lastSeen < before);
    }

    void clear() {
        villagers.clear();
    }

    int size() {
        return villagers.size();
    }
}

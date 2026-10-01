package com.nyr.guardian.dupesentry;

import com.nyr.guardian.common.Alerts;
import com.nyr.guardian.common.Messages;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/** Counts what each guard stopped since start and tells staff: chat, console and alerts.log, rate-limited per kind and chunk. */
final class Reporter {

    /** Players further away than this are not named as the one nearest to a dupe. */
    private static final double NEAREST_RANGE = 64;

    private final Map<Guard, LongAdder> counts = new EnumMap<>(Guard.class);
    private final Alerts alerts;
    private final Messages messages;
    private volatile boolean alertContainers;

    Reporter(Alerts alerts, Messages messages) {
        this.alerts = alerts;
        this.messages = messages;
        for (Guard guard : Guard.values()) {
            counts.put(guard, new LongAdder());
        }
    }

    void alertContainers(boolean alert) {
        this.alertContainers = alert;
    }

    long count(Guard guard) {
        return counts.get(guard).sum();
    }

    /**
     * Counts one stopped dupe and alerts staff. {@code dupe} is the already formatted name, such as "TNT duper".
     *
     * @return whether an alert went out (false while the same kind in the same chunk is cooling down)
     */
    boolean stopped(Guard guard, String dupe, Location where) {
        counts.get(guard).increment();
        if (guard == Guard.CONTAINER_DESYNC && !alertContainers) {
            return false;
        }
        World world = where.getWorld();
        String worldName = world == null ? "?" : world.getName();
        String key = guard.key() + ':' + dupe + ':' + worldName + ':' + (where.getBlockX() >> 4) + ':' + (where.getBlockZ() >> 4);
        String player = nearestPlayer(world, where);
        return alerts.send(key, messages.format("alert", "dupe", dupe, "world", worldName, "x", where.getBlockX(), "y", where.getBlockY(),
            "z", where.getBlockZ(), "player", player == null ? messages.format("alert-no-player") : player));
    }

    /**
     * The nearest player in the same world within 64 blocks. It reads the world's player list and their positions rather than
     * searching entities, which Folia forbids outside the region that owns them.
     */
    static String nearestPlayer(World world, Location where) {
        if (world == null) {
            return null;
        }
        String nearest = null;
        double best = NEAREST_RANGE * NEAREST_RANGE;
        for (Player player : world.getPlayers()) {
            Location at = player.getLocation();
            double distance = at.distanceSquared(where);
            if (distance <= best) {
                best = distance;
                nearest = player.getName();
            }
        }
        return nearest;
    }
}

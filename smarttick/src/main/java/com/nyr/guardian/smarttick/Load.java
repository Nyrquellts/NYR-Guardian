package com.nyr.guardian.smarttick;

import java.util.Locale;
import org.bukkit.Location;

/** How far behind the server is: one reading for the whole server, or one per region on Folia. */
interface Load {

    /** Milliseconds per tick (higher is worse) or ticks per second (lower is worse). */
    enum Unit {
        MSPT, TPS
    }

    /** One reading and its unit. */
    record Reading(double value, Unit unit) {

        String display() {
            return unit == Unit.MSPT ? String.format(Locale.ROOT, "%.1f ms per tick", value) : String.format(Locale.ROOT, "%.2f TPS", value);
        }
    }

    /** True when one reading covers the whole server (Paper, Purpur, Spigot); false when each region has its own (Folia). */
    boolean global();

    /**
     * The reading now, or null while there is none yet. A regional load reads the region that owns {@code where} and must be
     * called on that region's thread; a global load ignores {@code where}.
     */
    Reading read(Location where);

    /** Called once per tick on the global thread, before anything reads. */
    default void tick() {
    }

    /** Called when the plugin starts or reloads, before the first tick. */
    default void restart() {
    }

    /** Where the reading comes from, for /smarttick. */
    String source();
}

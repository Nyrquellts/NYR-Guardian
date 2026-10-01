package com.nyr.guardian.smarttick;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.ConfigurationSection;

/**
 * What config.yml says, checked once per start: thresholds that contradict each other are corrected and named in
 * {@link #problems()}, so a typo cannot leave villagers asleep forever or never let them sleep.
 */
record Settings(double msptSleepAt, double msptWakeAt, double tpsSleepBelow, double tpsWakeAt, long holdMillis,
                long scanMillis, int batchPerSecond, long stationaryMillis, boolean skipVillagersWithBeds,
                boolean restockWhileAsleep, List<String> problems) {

    static Settings from(ConfigurationSection root) {
        List<String> problems = new ArrayList<>();
        double msptSleepAt = root.getDouble("mspt.sleep-at", 40);
        double msptWakeAt = root.getDouble("mspt.wake-at", 35);
        if (msptWakeAt > msptSleepAt) {
            problems.add("mspt.wake-at (" + msptWakeAt + ") is above mspt.sleep-at (" + msptSleepAt + "); using " + msptSleepAt + " for both");
            msptWakeAt = msptSleepAt;
        }
        double tpsSleepBelow = root.getDouble("tps.sleep-below", 19.0);
        double tpsWakeAt = root.getDouble("tps.wake-at", 19.8);
        if (tpsWakeAt < tpsSleepBelow) {
            problems.add("tps.wake-at (" + tpsWakeAt + ") is below tps.sleep-below (" + tpsSleepBelow + "); using " + tpsSleepBelow + " for both");
            tpsWakeAt = tpsSleepBelow;
        }
        double hold = root.getDouble("hold-seconds", 5);
        double scan = root.getDouble("scan-seconds", 5);
        if (scan < 1 || scan > 300) {
            problems.add("scan-seconds must be 1 to 300, not " + scan);
            scan = Math.max(1, Math.min(300, scan));
        }
        int batch = root.getInt("batch-per-second", 200);
        if (batch < 1) {
            problems.add("batch-per-second must be at least 1, not " + batch);
            batch = 1;
        }
        double stationary = root.getDouble("stationary-minutes", 5);
        return new Settings(msptSleepAt, msptWakeAt, tpsSleepBelow, tpsWakeAt,
            Math.max(0, Math.round(hold * 1000)),
            Math.round(scan * 1000),
            batch,
            Math.max(0, Math.round(stationary * 60_000)),
            root.getBoolean("skip-villagers-with-beds", true),
            root.getBoolean("restock-while-asleep", true),
            List.copyOf(problems));
    }

    /**
     * Where a reading stands against the thresholds of its unit. The rule is NYR-Lang: {@code src/main/nyr/pressure.nyr},
     * compiled by nyrc into {@link PressureRules}; for a reading that is a number exactly one of its rules holds.
     */
    Pressure classify(Load.Reading reading) {
        if (reading == null || Double.isNaN(reading.value())) {
            return Pressure.UNKNOWN;
        }
        PressureRules.Result decided = PressureRules.evaluate(new PressureRules.Context()
            .value(reading.value()).unit(reading.unit() == Load.Unit.MSPT ? 0 : 1)
            .mspt_sleep_at(msptSleepAt).mspt_wake_at(msptWakeAt)
            .tps_sleep_below(tpsSleepBelow).tps_wake_at(tpsWakeAt));
        for (PressureRules.Signal signal : decided.signals()) {
            if (signal.id() == PressureRules.High) {
                return Pressure.HIGH;
            }
            return signal.id() == PressureRules.Low ? Pressure.LOW : Pressure.BETWEEN;
        }
        return Pressure.UNKNOWN;
    }

    /** A reading at or past the sleep threshold, one at or past the wake threshold, one between them, or none. */
    enum Pressure {
        HIGH, LOW, BETWEEN, UNKNOWN
    }
}

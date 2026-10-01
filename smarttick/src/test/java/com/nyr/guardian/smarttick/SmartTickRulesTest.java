package com.nyr.guardian.smarttick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * SmartTick's two rules are NYR-Lang ({@code src/main/nyr/pressure.nyr} and {@code hysteresis.nyr}, compiled by nyrc into
 * {@link PressureRules} and {@link HysteresisRules}). These tests run the classes that now use them side by side with the
 * code they replaced, kept below as the oracle, and require the same answer to every question, every time.
 */
class SmartTickRulesTest {

    /** Hysteresis as it was written by hand, before its rule moved into NYR-Lang. */
    private static final class Before {
        private boolean behind;
        private long highSince = -1;
        private long lowSince = -1;
        private long behindSince = Long.MIN_VALUE / 2;
        private long recoveredAt = Long.MIN_VALUE / 2;
        private long minBehind;

        Before(boolean behind) {
            this.behind = behind;
        }

        long heldFor(long now) {
            return behind ? Math.max(0, behindSince + minBehind - now) : 0;
        }

        void forgetRelapses() {
            minBehind = 0;
            recoveredAt = Long.MIN_VALUE / 2;
        }

        boolean update(Settings.Pressure pressure, long now, long holdMillis) {
            if (pressure == Settings.Pressure.UNKNOWN) {
                return false;
            }
            if (!behind) {
                lowSince = -1;
                if (pressure != Settings.Pressure.HIGH) {
                    highSince = -1;
                    return false;
                }
                if (highSince < 0) {
                    highSince = now;
                }
                if (now - highSince >= holdMillis) {
                    boolean relapse = now - recoveredAt <= Hysteresis.RELAPSE_MILLIS;
                    minBehind = !relapse ? 0 : minBehind == 0 ? Hysteresis.FIRST_BACKOFF_MILLIS
                        : Math.min(Hysteresis.MAX_BACKOFF_MILLIS, minBehind * 2);
                    behind = true;
                    behindSince = now;
                    highSince = -1;
                    return true;
                }
                return false;
            }
            highSince = -1;
            if (pressure != Settings.Pressure.LOW) {
                lowSince = -1;
                return false;
            }
            if (lowSince < 0) {
                lowSince = now;
            }
            if (now - lowSince >= holdMillis && now - behindSince >= minBehind) {
                behind = false;
                recoveredAt = now;
                lowSince = -1;
                return true;
            }
            return false;
        }
    }

    /** Settings.classify as it was written by hand, before its rule moved into NYR-Lang. */
    private static Settings.Pressure classifiedBefore(Settings s, Load.Reading reading) {
        if (reading == null || Double.isNaN(reading.value())) {
            return Settings.Pressure.UNKNOWN;
        }
        double value = reading.value();
        if (reading.unit() == Load.Unit.MSPT) {
            if (value >= s.msptSleepAt()) {
                return Settings.Pressure.HIGH;
            }
            return value <= s.msptWakeAt() ? Settings.Pressure.LOW : Settings.Pressure.BETWEEN;
        }
        if (value < s.tpsSleepBelow()) {
            return Settings.Pressure.HIGH;
        }
        return value >= s.tpsWakeAt() ? Settings.Pressure.LOW : Settings.Pressure.BETWEEN;
    }

    @Test
    void theRelapseConstantsAreTheRulesOwn() {
        assertEquals(5 * 60_000L, Hysteresis.RELAPSE_MILLIS);
        assertEquals(2 * 60_000L, Hysteresis.FIRST_BACKOFF_MILLIS);
        assertEquals(60 * 60_000L, Hysteresis.MAX_BACKOFF_MILLIS);
    }

    @Test
    void hysteresisDecidesWhatTheHandWrittenCodeDecided() {
        Random random = new Random(20260925L);
        Settings.Pressure[] readings = Settings.Pressure.values();
        long[] holds = {0, 1, 1_000, 5_000, 60_000};
        int changes = 0;
        long longestSleep = 0;
        for (int run = 0; run < 3_000; run++) {
            boolean startBehind = random.nextInt(4) == 0;
            Hysteresis state = new Hysteresis(startBehind);
            Before before = new Before(startBehind);
            long hold = holds[random.nextInt(holds.length)];
            long clock = random.nextInt(3) == 0 ? 0 : random.nextLong(1L << 45);
            for (int step = 0; step < 200; step++) {
                clock += switch (random.nextInt(5)) {
                    case 0 -> 0;
                    case 1 -> random.nextInt(2_000);
                    case 2 -> random.nextInt(20_000);
                    case 3 -> 290_000 + random.nextInt(20_000);         // either side of the relapse window
                    default -> random.nextInt(400_000);
                };
                Settings.Pressure reading = readings[random.nextInt(readings.length)];
                if (random.nextInt(60) == 0) {
                    state.forgetRelapses();
                    before.forgetRelapses();
                }
                String where = "run " + run + ", step " + step + ": " + reading + " at " + clock + ", hold " + hold;
                boolean changed = before.update(reading, clock, hold);
                assertEquals(changed, state.update(reading, clock, hold), where);
                assertEquals(before.behind, state.behind(), where);
                assertEquals(before.minBehind, state.minimumSleepMillis(), where);
                assertEquals(before.heldFor(clock), state.heldFor(clock), where);
                changes += changed ? 1 : 0;
                longestSleep = Math.max(longestSleep, before.minBehind);
            }
        }
        assertTrue(changes > 20_000, "the runs change state often enough to mean something: " + changes);
        assertEquals(Hysteresis.MAX_BACKOFF_MILLIS, longestSleep, "some run relapsed all the way to the longest sleep");
    }

    @Test
    void pressureDecidesWhatTheHandWrittenCodeDecided() {
        Random random = new Random(7L);
        double[] odd = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -0.0};
        for (int i = 0; i < 100_000; i++) {
            Settings settings = new Settings(threshold(random, odd, 100), threshold(random, odd, 100),
                threshold(random, odd, 20), threshold(random, odd, 20), 5_000, 5_000, 200, 300_000, true, true, List.of());
            Load.Unit unit = random.nextBoolean() ? Load.Unit.MSPT : Load.Unit.TPS;
            Load.Reading reading = random.nextInt(25) == 0 ? null
                : new Load.Reading(threshold(random, odd, unit == Load.Unit.MSPT ? 100 : 20), unit);
            assertEquals(classifiedBefore(settings, reading), settings.classify(reading), settings + " " + reading);
        }
    }

    /** Mostly an ordinary value up to {@code top}, on a grid that hits the thresholds exactly; sometimes an odd one. */
    private static double threshold(Random random, double[] odd, int top) {
        return random.nextInt(12) == 0 ? odd[random.nextInt(odd.length)] : random.nextInt(top * 4 + 1) / 4.0;
    }
}

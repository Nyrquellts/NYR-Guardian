package com.nyr.guardian.smarttick;

/**
 * Whether the server (or one region) counts as behind. It becomes behind only after readings at or past the sleep threshold
 * for the whole hold time, and recovers only after readings at or past the wake threshold for the whole hold time, so one
 * spike does nothing and a reading between the two thresholds keeps the current state.
 *
 * <p>When the sleeping villagers are themselves what put the server behind, it "recovers" only because they sleep, and
 * waking them brings the lag straight back. So a relapse (behind again within {@link #RELAPSE_MILLIS} of recovering) keeps
 * the next sleep going for at least {@link #FIRST_BACKOFF_MILLIS}, doubling with every further relapse up to
 * {@link #MAX_BACKOFF_MILLIS}; a recovery that holds for {@link #RELAPSE_MILLIS} forgets it.
 *
 * <p>That rule is NYR-Lang: {@code src/main/nyr/hysteresis.nyr}, compiled by nyrc into {@link HysteresisRules}. This class
 * keeps the state, hands it to the rules with each reading and applies what they decide.
 */
final class Hysteresis {

    static final long RELAPSE_MILLIS = HysteresisRules.Relapse;
    static final long FIRST_BACKOFF_MILLIS = HysteresisRules.FirstBackoff;
    static final long MAX_BACKOFF_MILLIS = HysteresisRules.MaxBackoff;

    private volatile boolean behind;
    private long highSince = -1;
    private long lowSince = -1;
    private long behindSince = Long.MIN_VALUE / 2;
    private long recoveredAt = Long.MIN_VALUE / 2;
    private volatile long minBehind;

    Hysteresis(boolean behind) {
        this.behind = behind;
    }

    boolean behind() {
        return behind;
    }

    /** How long this sleep lasts at least, because the last ones ended in a relapse; 0 when it may end at once. */
    long minimumSleepMillis() {
        return minBehind;
    }

    /** Milliseconds this sleep must still last at {@code now}, whatever the readings; 0 when none. */
    synchronized long heldFor(long now) {
        return behind ? Math.max(0, behindSince + minBehind - now) : 0;
    }

    /** Forgets past relapses (a reload brings new thresholds to judge them by); keeps whether it is behind now. */
    synchronized void forgetRelapses() {
        minBehind = 0;
        recoveredAt = Long.MIN_VALUE / 2;
    }

    /**
     * Feeds one reading's pressure at time {@code now} (milliseconds, any monotonic clock).
     *
     * @return true when this reading changed the state
     */
    synchronized boolean update(Settings.Pressure pressure, long now, long holdMillis) {
        HysteresisRules.Result decided = HysteresisRules.evaluate(new HysteresisRules.Context()
            .behind(behind ? 1 : 0).pressure(code(pressure)).now(now).hold(holdMillis)
            .high_since(highSince).low_since(lowSince).behind_since(behindSince)
            .recovered_at(recoveredAt).min_behind(minBehind));
        // Every value is a whole number of milliseconds well inside 2^53, so the rules' doubles hold it exactly.
        for (HysteresisRules.Update update : decided.updates()) {
            long value = (long) update.value();
            switch (update.var()) {
                case behind -> behind = value != 0;
                case high_since -> highSince = value;
                case low_since -> lowSince = value;
                case behind_since -> behindSince = value;
                case recovered_at -> recoveredAt = value;
                case min_behind -> minBehind = value;
            }
        }
        return !decided.signals().isEmpty();
    }

    /** A reading as the rules number it. */
    private static long code(Settings.Pressure pressure) {
        return switch (pressure) {
            case HIGH -> HysteresisRules.High;
            case LOW -> HysteresisRules.Low;
            case BETWEEN -> HysteresisRules.Between;
            case UNKNOWN -> HysteresisRules.Unknown;
        };
    }
}

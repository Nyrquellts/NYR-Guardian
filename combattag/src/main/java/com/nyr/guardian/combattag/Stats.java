package com.nyr.guardian.combattag;

import java.util.concurrent.atomic.LongAdder;

/** Counts since the plugin started, for /combattag status. */
final class Stats {
    final LongAdder tags = new LongAdder();
    final LongAdder commandsRefused = new LongAdder();
    final LongAdder dummies = new LongAdder();
    final LongAdder killed = new LongAdder();
    final LongAdder returned = new LongAdder();
    final LongAdder expired = new LongAdder();
    final LongAdder applied = new LongAdder();
    final LongAdder spawnFailed = new LongAdder();
    final LongAdder recordFailed = new LongAdder();
}

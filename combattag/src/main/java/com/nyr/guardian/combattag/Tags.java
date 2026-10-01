package com.nyr.guardian.combattag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Who is in combat and until when, per player UUID, in memory. Safe from any thread: Folia tags the victim of a hit on the
 * victim's region thread and the shooter of an arrow may live on another.
 */
final class Tags {

    /** A tag: when this combat started and when it ends (wall-clock milliseconds). */
    record Tag(long since, long until) {
    }

    private final Map<UUID, Tag> tags = new ConcurrentHashMap<>();

    /**
     * Puts the player in combat until {@code now + millis}; a hit during combat restarts the time.
     *
     * @return true when the player was not in combat before this tag
     */
    boolean tag(UUID player, long now, long millis) {
        boolean[] fresh = {false};
        tags.compute(player, (id, old) -> {
            if (old == null || old.until() <= now) {
                fresh[0] = true;
                return new Tag(now, now + millis);
            }
            return new Tag(old.since(), now + millis);
        });
        return fresh[0];
    }

    long remainingMillis(UUID player, long now) {
        Tag tag = tags.get(player);
        return tag == null ? 0 : Math.max(0, tag.until() - now);
    }

    boolean tagged(UUID player, long now) {
        return remainingMillis(player, now) > 0;
    }

    /** @return whether the player was in combat */
    boolean untag(UUID player, long now) {
        Tag tag = tags.remove(player);
        return tag != null && tag.until() > now;
    }

    /** Removes every tag that has run out and returns whose they were; each one is returned by exactly one call. */
    List<UUID> expire(long now) {
        List<UUID> ended = new ArrayList<>();
        for (Map.Entry<UUID, Tag> entry : tags.entrySet()) {
            Tag tag = entry.getValue();
            if (tag.until() <= now && tags.remove(entry.getKey(), tag)) {
                ended.add(entry.getKey());
            }
        }
        return ended;
    }

    /** Each player still in combat with the milliseconds left. */
    void forEachLive(long now, BiConsumer<UUID, Long> action) {
        for (Map.Entry<UUID, Tag> entry : tags.entrySet()) {
            long left = entry.getValue().until() - now;
            if (left > 0) {
                action.accept(entry.getKey(), left);
            }
        }
    }

    int count(long now) {
        int[] count = {0};
        forEachLive(now, (id, left) -> count[0]++);
        return count[0];
    }

    void clear() {
        tags.clear();
    }

    /** Whole seconds shown to players: 14.2 s left shows as 15. */
    static int seconds(long millis) {
        return (int) ((Math.max(0, millis) + 999) / 1000);
    }
}

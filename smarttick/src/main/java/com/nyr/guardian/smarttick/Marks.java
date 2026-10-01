package com.nyr.guardian.smarttick;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * The mark SmartTick leaves on a villager it put to sleep, saved with the entity. A villager without it was never touched by
 * SmartTick: if it is unaware, another plugin did that, and SmartTick leaves it alone.
 */
final class Marks {

    /** When SmartTick put the villager to sleep, in epoch milliseconds. */
    static final NamespacedKey ASLEEP = key("asleep");
    /** Restocks done while asleep: {Minecraft day, restocks that day, world time of the last one}. */
    static final NamespacedKey RESTOCKS = key("restocks");

    private Marks() {
    }

    private static NamespacedKey key(String name) {
        NamespacedKey key = NamespacedKey.fromString("nyrsmarttick:" + name);
        if (key == null) {
            throw new IllegalStateException("invalid key " + name);
        }
        return key;
    }

    static boolean asleep(Entity entity) {
        return entity.getPersistentDataContainer().has(ASLEEP, PersistentDataType.LONG);
    }

    /** Puts the mob's brain to sleep and marks it as SmartTick's. */
    static void sleep(Mob mob) {
        mob.getPersistentDataContainer().set(ASLEEP, PersistentDataType.LONG, System.currentTimeMillis());
        mob.setAware(false);
    }

    /**
     * Wakes the mob's brain and removes the sleep mark. The restock record stays: it keeps a villager that sleeps again the
     * same day at the game's two restocks a day.
     */
    static void wake(Mob mob) {
        mob.setAware(true);
        mob.getPersistentDataContainer().remove(ASLEEP);
    }

    /** Removes a sleep mark someone else made stale by waking the mob themselves. */
    static void forget(Mob mob) {
        PersistentDataContainer data = mob.getPersistentDataContainer();
        data.remove(ASLEEP);
    }
}

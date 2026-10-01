package com.nyr.guardian.smarttick;

import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.MerchantInventory;

/**
 * Keeps sleeping villagers safe where the scan cannot: chunks loading and unloading, players trading, job sites breaking,
 * and villagers turning into something else. Every event arrives on the thread that owns its entities.
 */
final class VillagerListener implements Listener {

    private final Engine engine;
    private final Engine.Totals totals;

    VillagerListener(Engine engine, Engine.Totals totals) {
        this.engine = engine;
        this.totals = totals;
    }

    /**
     * A chunk's entities loaded. A villager saved asleep (after a hard stop, or on Folia, which cannot wake villagers while it
     * stops) wakes now, unless it may sleep and the load still says so. Any other mob carrying the mark wakes.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    void onEntitiesLoad(EntitiesLoadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (!(entity instanceof Mob mob) || !Marks.asleep(mob)) {
                continue;
            }
            if (mob instanceof Villager villager && engine.keepAsleepOnLoad(villager)) {
                totals.keptOnLoad.increment();
                continue;
            }
            Marks.wake(mob);
            totals.wokenOnLoad.increment();
        }
    }

    /**
     * A chunk's entities are about to be saved and unloaded: sleeping villagers wake first, so a villager is never saved asleep
     * in a chunk SmartTick is not watching. If the plugin is removed later, nobody is left asleep out of reach.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    void onEntitiesUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) {
            if (entity instanceof Mob mob && Marks.asleep(mob)) {
                Marks.wake(mob);
                totals.wokenOnUnload.increment();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onTradeOpen(InventoryOpenEvent event) {
        Villager villager = merchant(event.getInventory(), event.getPlayer());
        if (villager != null) {
            engine.traded(villager);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    void onTradeClose(InventoryCloseEvent event) {
        Villager villager = merchant(event.getInventory(), event.getPlayer());
        if (villager != null) {
            engine.traded(villager);
        }
    }

    /**
     * The villager behind a trade window. From 1.21 the window's merchant is the villager itself; Paper 1.20.6 hands a
     * separate merchant wrapper instead, so there it is the villager near the player whose trading partner is that player
     * (the game sets it before the window opens and clears it only after it closed).
     */
    private static Villager merchant(Inventory inventory, HumanEntity player) {
        if (!(inventory instanceof MerchantInventory trades)) {
            return null;
        }
        if (trades.getMerchant() instanceof Villager villager) {
            return villager;
        }
        for (Entity nearby : player.getNearbyEntities(8, 8, 8)) {
            if (nearby instanceof Villager villager && player.equals(villager.getTrader())) {
                return villager;
            }
        }
        return null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onBlockBreak(BlockBreakEvent event) {
        if (Eligibility.WORKSTATIONS.contains(event.getBlock().getType())) {
            engine.jobSiteBroken(event.getBlock());
        }
    }

    /** A sleeping villager turned into a zombie villager or a witch: the new mob must not inherit the sleep. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onTransform(EntityTransformEvent event) {
        for (Entity transformed : event.getTransformedEntities()) {
            if (transformed instanceof Mob mob && Marks.asleep(mob)) {
                Marks.wake(mob);
                totals.woken.increment();
            }
        }
    }
}

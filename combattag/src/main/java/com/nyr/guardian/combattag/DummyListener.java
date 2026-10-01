package com.nyr.guardian.combattag;

import org.bukkit.entity.LivingEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Logging out in combat, coming back, and everything that happens to a dummy in between. */
final class DummyListener implements Listener {

    private final CombatTagPlugin plugin;

    DummyListener(CombatTagPlugin plugin) {
        this.plugin = plugin;
    }

    /** A kick (by staff or another plugin) is seen before the quit it causes. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        plugin.dummies().kicked(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        plugin.dummies().quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        plugin.dummies().join(event.getPlayer());
    }

    /**
     * A dummy's own equipment and loot are copies or junk: cleared first, before any other plugin (a graves plugin, say) sees
     * them, and again at the end in case one added something back.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void clearDropsFirst(EntityDeathEvent event) {
        if (plugin.body().isDummy(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDummyDeath(EntityDeathEvent event) {
        LivingEntity body = event.getEntity();
        if (!plugin.body().isDummy(body)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        plugin.dummies().died(body);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDummyHurt(EntityDamageEvent event) {
        if (event.getEntity() instanceof LivingEntity body && plugin.body().isDummy(body)) {
            plugin.dummies().hurt(body);
        }
    }

    /**
     * The dummy is marked before it enters the world, so a spawn another plugin cancelled (a mob limiter, a region that denies
     * mobs) is recognised and allowed: a combat logger's dummy is not a natural mob.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDummySpawn(EntitySpawnEvent event) {
        if (event.isCancelled() && plugin.body().isDummy(event.getEntity())) {
            event.setCancelled(false);
        }
    }

    /** A husk drowning into a zombie or a villager struck by lightning would leave the dummy's place and its mark behind. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTransform(EntityTransformEvent event) {
        if (plugin.body().isDummy(event.getEntity())) {
            event.setCancelled(true);
        }
    }
}

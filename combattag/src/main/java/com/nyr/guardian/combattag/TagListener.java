package com.nyr.guardian.combattag;

import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/** Tagging (hits, harmful potions and clouds), untagging on death, and commands refused in combat. */
final class TagListener implements Listener {

    private final CombatTagPlugin plugin;

    TagListener(CombatTagPlugin plugin) {
        this.plugin = plugin;
    }

    /** MONITOR with ignoreCancelled: a hit a region plugin cancelled tags nobody. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        Player attacker = Attackers.player(event.getDamager());
        if (attacker != null) {
            plugin.tagPair(victim, attacker);
            return;
        }
        if (plugin.currentSettings().tagOnMobs()) {
            Mob mob = Attackers.mob(event.getDamager());
            if (mob != null && !plugin.body().isDummy(mob)) {
                plugin.tagAlone(victim);
            }
        }
    }

    /** A harmful splash potion thrown by one player that reaches another tags both. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSplash(PotionSplashEvent event) {
        if (!plugin.currentSettings().tagOnHarmfulPotions()) {
            return;
        }
        ThrownPotion potion = event.getPotion();
        if (!(potion.getShooter() instanceof Player thrower) || !Attackers.harmful(potion.getEffects())) {
            return;
        }
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player victim && event.getIntensity(victim) > 0) {
                plugin.tagPair(victim, thrower);
            }
        }
    }

    /** The same for a lingering potion's cloud, each time it applies its effects. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCloud(AreaEffectCloudApplyEvent event) {
        if (!plugin.currentSettings().tagOnHarmfulPotions()) {
            return;
        }
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player thrower) || !Attackers.harmful(cloud)) {
            return;
        }
        for (LivingEntity affected : event.getAffectedEntities()) {
            if (affected instanceof Player victim) {
                plugin.tagPair(victim, thrower);
            }
        }
    }

    /** The death message of a player killed on joining because their dummy died. */
    @EventHandler(priority = EventPriority.NORMAL)
    @SuppressWarnings("deprecation")
    public void onDeathMessage(PlayerDeathEvent event) {
        String message = plugin.dummies().takeDeathMessage(event.getEntity().getUniqueId());
        if (message != null) {
            event.setDeathMessage(message);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        plugin.tags().untag(event.getEntity().getUniqueId(), plugin.now());
    }

    /** LOWEST, so a refused command never reaches the plugin that owns it. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        long left = plugin.tags().remainingMillis(player.getUniqueId(), plugin.now());
        if (left <= 0 || player.hasPermission(plugin.permission("bypass"))) {
            return;
        }
        String root = CommandRules.root(event.getMessage());
        if (plugin.currentSettings().commands().blocks(root)) {
            event.setCancelled(true);
            plugin.stats().commandsRefused.increment();
            plugin.messages().send(player, "command-blocked", "command", root, "seconds", Tags.seconds(left));
        }
    }
}

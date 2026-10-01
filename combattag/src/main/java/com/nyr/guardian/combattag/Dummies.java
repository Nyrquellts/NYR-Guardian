package com.nyr.guardian.combattag;

import com.nyr.guardian.common.Text;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Combat-log dummies: spawned when a tagged player logs out, killed by other players, taken back when the owner returns first,
 * removed when their time runs out. Every entry point runs on the thread that owns what it touches (Folia).
 */
final class Dummies {

    private final CombatTagPlugin plugin;
    private final Map<UUID, DummyState> byOwner = new ConcurrentHashMap<>();
    private final Set<UUID> kicked = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> deathMessages = new ConcurrentHashMap<>();

    Dummies(CombatTagPlugin plugin) {
        this.plugin = plugin;
    }

    void kicked(Player player) {
        kicked.add(player.getUniqueId());
    }

    /** PlayerQuitEvent (MONITOR), on the leaving player's thread. The player is untagged afterwards whatever happens here. */
    void quit(Player player) {
        UUID id = player.getUniqueId();
        boolean wasKicked = kicked.remove(id);
        long now = plugin.now();
        try {
            if (!plugin.tags().tagged(id, now)) {
                return;
            }
            Settings settings = plugin.currentSettings();
            if (!settings.dummyEnabled() || plugin.serverStopping() || (wasKicked && !settings.dummyOnKick())) {
                return;
            }
            if (player.isDead() || player.getHealth() <= 0 || player.hasPermission(plugin.permission("bypass"))
                || !plugin.worlds().allows(player.getWorld().getName())) {
                return;
            }
            spawnFor(player, settings, now);
        } finally {
            plugin.tags().untag(id, now);
        }
    }

    private void spawnFor(Player player, Settings settings, long now) {
        Snapshot snapshot = Snapshot.of(player, plugin.maxHealth());
        String shown = Text.color(settings.dummyName().replace("{player}", player.getName()));
        String description = Text.color(settings.dummyDescription().replace("{player}", player.getName()));
        DummyBody.Spawned spawned = plugin.body().spawn(player, snapshot, settings, shown, description);
        Location at = snapshot.location();
        if (spawned == null) {
            plugin.stats().spawnFailed.increment();
            plugin.alerts().send("spawn-failed-" + player.getUniqueId(), plugin.messages().format("alert-spawn-failed", "player", player.getName(),
                "world", worldName(at), "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ()));
            return;
        }
        DummyState state = new DummyState(snapshot, spawned.entity(), spawned.kind(), now, now + settings.dummyMillis());
        DummyState previous = byOwner.put(player.getUniqueId(), state);
        if (previous != null && previous.expire()) {
            removeBody(previous);
        }
        plugin.stats().dummies.increment();
        plugin.alerts().send("logout-" + player.getUniqueId(), plugin.messages().format("alert-logged-out", "player", player.getName(),
            "world", worldName(at), "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ(), "seconds", settings.dummySeconds(),
            "kind", spawned.kind()));
    }

    /** PlayerJoinEvent (LOWEST), on the joining player's thread, before anything else can see their inventory. */
    void join(Player player) {
        UUID id = player.getUniqueId();
        kicked.remove(id);
        DummyState state = byOwner.get(id);
        DummyState.Join found = state == null ? DummyState.Join.NOTHING : state.join();
        switch (found.kind()) {
            case RETURN -> takeBack(player, state);
            case APPLY -> apply(player, found.record(), state);
            case NOTHING -> {
                if (state != null && state.phase() != DummyState.Phase.STANDING) {
                    byOwner.remove(id, state);
                }
                KillRecord onDisk;
                try {
                    onDisk = plugin.store().read(id);
                } catch (IOException unreadable) {
                    plugin.getLogger().log(Level.SEVERE, "Could not read the kill record of " + player.getName() + " (" + id
                        + "); they keep their items this time. Fix or delete the file.", unreadable);
                    return;
                }
                if (onDisk != null) {
                    apply(player, onDisk, null);
                }
            }
        }
    }

    /**
     * The dummy died while its owner was away: what it dropped is taken from the owner, their data saved, the record deleted,
     * then they die (their inventory is already empty, so that death drops nothing again).
     */
    private void apply(Player player, KillRecord record, DummyState state) {
        UUID id = player.getUniqueId();
        if (record.itemsDropped()) {
            player.getInventory().clear();
        }
        if (record.experienceDropped()) {
            player.setLevel(0);
            player.setExp(0f);
            player.setTotalExperience(0);
        }
        boolean saved;
        try {
            player.saveData();
            saved = true;
        } catch (RuntimeException cannotSave) {
            saved = false;
            plugin.getLogger().log(Level.SEVERE, "Could not save " + player.getName() + "'s data after taking what their dummy dropped;"
                + " keeping the kill record so a crash now cannot give the items back.", cannotSave);
        }
        if (saved) {
            try {
                plugin.store().delete(id);
            } catch (IOException cannotDelete) {
                plugin.getLogger().warning("Could not delete the kill record of " + player.getName() + " (" + cannotDelete.getMessage()
                    + "); they will die again on their next join.");
            }
        }
        if (state != null) {
            byOwner.remove(id, state);
        }
        plugin.stats().applied.increment();
        String deathMessage = plugin.messages().format("died-while-logged", "player", player.getName(), "killer", record.killer());
        plugin.messages().send(player, record.itemsDropped() ? "logged-out-killed" : "logged-out-killed-kept", "killer", record.killer());
        // Newer versions (1.21.11, not 1.20.6) save who last hit a player with the player and restore it on joining, so a death
        // right after joining would give whoever hit them before they logged out a second kill (the dummy's killer got one).
        // Paper can forget that attacker; Spigot cannot, so there the death waits until the game has forgotten it.
        long delay = SET_KILLER == null ? FORGET_TICKS : 1L;
        plugin.scheduler().runAtEntityLater(player, () -> {
            if (player.isOnline() && !player.isDead()) {
                forgetAttacker(player);
                // the death message is waiting only while this death happens (the death event fires inside setHealth)
                if (!deathMessage.isEmpty()) {
                    deathMessages.put(id, deathMessage);
                }
                try {
                    player.setHealth(0);
                } finally {
                    deathMessages.remove(id);
                }
            }
        }, delay);
    }

    /** How long the game remembers who last hit a player (100 ticks), and one more. */
    static final long FORGET_TICKS = 101L;

    /** Paper: LivingEntity#setKiller(Player); null makes the next death nobody's kill. Spigot has no such method. */
    private static final java.lang.reflect.Method SET_KILLER = setKiller();

    private static java.lang.reflect.Method setKiller() {
        try {
            return LivingEntity.class.getMethod("setKiller", Player.class);
        } catch (NoSuchMethodException | SecurityException absent) {
            return null;
        }
    }

    private static void forgetAttacker(Player player) {
        if (SET_KILLER == null) {
            return;
        }
        try {
            SET_KILLER.invoke(player, (Object) null);
        } catch (ReflectiveOperationException | RuntimeException notHere) {
            // the death may then also count for the last attacker, as it would in the game
        }
    }

    /** The owner came back before their dummy died: it goes, they get its health and are in combat again. */
    private void takeBack(Player player, DummyState state) {
        // Straight away, on this thread (the player's own): logging out never heals, whatever happens next.
        setHealth(player, state.health);
        Entity body = state.body;
        if (body == null) {
            finishTakeBack(player, state, state.health);
            return;
        }
        plugin.scheduler().runAtEntityWithFallback(body, task -> {
            double health = state.health;
            if (body instanceof LivingEntity living && !living.isDead()) {
                health = living.getHealth();
                state.health = health;
            }
            body.remove();
            double carried = health;
            plugin.atEntity(player, () -> finishTakeBack(player, state, carried));
        }, () -> plugin.atEntity(player, () -> finishTakeBack(player, state, state.health)));
    }

    private void finishTakeBack(Player player, DummyState state, double health) {
        byOwner.remove(state.owner, state);
        if (!player.isOnline()) {
            return;
        }
        setHealth(player, health);
        plugin.stats().returned.increment();
        plugin.tagAgain(player);
    }

    private void setHealth(Player player, double health) {
        double max = plugin.maxHealth().of(player);
        player.setHealth(Math.max(0.5, Math.min(health, max)));
    }

    /** EntityDeathEvent (MONITOR, not cancelled) of a marked entity, on the dummy's thread. */
    void died(LivingEntity body) {
        UUID owner = plugin.body().ownerOf(body);
        DummyState state = owner == null ? null : byOwner.get(owner);
        if (state == null || !body.getUniqueId().equals(state.bodyId)) {
            return;
        }
        World world = body.getWorld();
        Location at = body.getLocation();
        boolean keep = Boolean.TRUE.equals(world.getGameRuleValue(GameRule.KEEP_INVENTORY));
        Player killer = body.getKiller();
        String killerName = killer != null ? killer.getName() : causeOf(body);
        List<ItemStack> drops = state.snapshot.drops();
        int experience = state.snapshot.experienceDrop();
        KillRecord record = new KillRecord(owner, state.ownerName, killerName, killer == null ? null : killer.getUniqueId(), plugin.now(),
            world.getName(), at.getX(), at.getY(), at.getZ(), !keep, !keep, keep ? 0 : drops.size(), keep ? 0 : experience);
        switch (state.kill(record, plugin.store())) {
            case LOST -> {
                return;
            }
            case WRITE_FAILED -> {
                byOwner.remove(owner, state);
                plugin.stats().recordFailed.increment();
                plugin.getLogger().log(Level.SEVERE, "Could not write the kill record of " + state.ownerName + "'s dummy, so nothing was"
                    + " dropped and " + state.ownerName + " keeps everything (no duplicate, no loss).", state.failure());
                plugin.alerts().send("record-failed-" + owner, plugin.messages().format("alert-record-failed", "player", state.ownerName));
                return;
            }
            case KILLED -> {
                // the record is on disk: now the drops
            }
        }
        if (!keep) {
            for (ItemStack item : drops) {
                world.dropItemNaturally(at, item);
            }
            if (experience > 0) {
                world.spawn(at, ExperienceOrb.class, orb -> orb.setExperience(experience));
            }
        }
        plugin.stats().killed.increment();
        if (killer != null) {
            plugin.atEntity(killer, () -> killer.incrementStatistic(Statistic.PLAYER_KILLS));
        }
        plugin.broadcast(plugin.messages().format("dummy-killed", "player", state.ownerName, "killer", killerName));
        plugin.alerts().send("killed-" + owner, plugin.messages().format(keep ? "alert-dummy-killed-kept" : "alert-dummy-killed",
            "player", state.ownerName, "killer", killerName, "world", world.getName(), "x", at.getBlockX(), "y", at.getBlockY(),
            "z", at.getBlockZ(), "stacks", drops.size(), "experience", experience));
    }

    /** Who or what killed a dummy no player killed: a mob's name, or the damage cause ("lava", "fall"). */
    @SuppressWarnings("deprecation")
    private static String causeOf(LivingEntity body) {
        EntityDamageEvent last = body.getLastDamageCause();
        if (last instanceof EntityDamageByEntityEvent byEntity) {
            Entity damager = byEntity.getDamager();
            if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
                damager = shooter;
            }
            return damager.getName();
        }
        if (last != null) {
            return last.getCause().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return "unknown";
    }

    /** After a dummy was hurt, its health is read again on its own thread (the next tick, once the damage is applied). */
    void hurt(LivingEntity body) {
        UUID owner = plugin.body().ownerOf(body);
        DummyState state = owner == null ? null : byOwner.get(owner);
        if (state == null || !body.getUniqueId().equals(state.bodyId)) {
            return;
        }
        plugin.scheduler().runAtEntity(body, task -> {
            if (body.isValid() && !body.isDead()) {
                state.health = body.getHealth();
            }
        });
    }

    /** Every second: dummies whose time is up are removed; their owners keep everything. */
    void tick(long now) {
        for (DummyState state : byOwner.values()) {
            if (now >= state.until && state.expire()) {
                byOwner.remove(state.owner, state);
                removeBody(state);
                plugin.stats().expired.increment();
            }
        }
    }

    /** The plugin is being disabled: every standing dummy goes and its owner keeps everything. */
    void endAll() {
        for (DummyState state : byOwner.values()) {
            if (state.expire()) {
                removeBody(state);
            }
        }
        byOwner.clear();
        kicked.clear();
        deathMessages.clear();
    }

    private void removeBody(DummyState state) {
        Entity body = state.body;
        if (body == null) {
            return;
        }
        try {
            plugin.atEntity(body, body::remove);
        } catch (RuntimeException schedulerGone) {
            // the server is stopping: a dummy is never saved with its chunk, so it is gone anyway
        }
    }

    /** The death message waiting for this player's death, taken once. */
    String takeDeathMessage(UUID player) {
        return deathMessages.remove(player);
    }

    Collection<DummyState> standing() {
        List<DummyState> out = new ArrayList<>();
        for (DummyState state : byOwner.values()) {
            if (state.phase() == DummyState.Phase.STANDING) {
                out.add(state);
            }
        }
        return out;
    }

    DummyState of(UUID owner) {
        return byOwner.get(owner);
    }

    private static String worldName(Location at) {
        return at.getWorld() == null ? "?" : at.getWorld().getName();
    }
}

package com.nyr.guardian.combattag;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.bukkit.Difficulty;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Ghast;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Phantom;
import org.bukkit.entity.Player;
import org.bukkit.entity.Slime;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;

/**
 * Spawns the body of a combat-log dummy. Where the server has mannequins (Minecraft 1.21.9 and newer: Paper, Folia, Purpur and
 * Spigot) it is a mannequin wearing the player's own profile; elsewhere, or if that fails, the configured fallback mob with its
 * AI off. The API compiled against has no mannequin, so every mannequin call goes through reflection and falls back when the
 * method is not there.
 */
final class DummyBody {

    /** What was spawned and what it is ("mannequin", "husk"...). */
    record Spawned(Entity entity, String kind) {
    }

    private final Logger logger;
    private final NamespacedKey mark;
    private final MaxHealth maxHealth;
    private final AtomicBoolean warnedMannequin = new AtomicBoolean();

    private final Class<? extends Entity> mannequin;
    /** Paper and Folia: Mannequin#setProfile(ResolvableProfile) with ResolvableProfile.resolvableProfile(paper PlayerProfile). */
    private final Method paperSetProfile;
    private final Method paperResolvable;
    private final Class<?> paperProfile;
    /** Spigot: Mannequin#setPlayerProfile(org.bukkit.profile.PlayerProfile). */
    private final Method spigotSetProfile;
    /** Paper: Mannequin#setDescription(Component), the text built by Adventure's legacy serializer. */
    private final Method paperSetDescription;
    private final Object legacySerializer;
    private final Method legacyDeserialize;
    /** Spigot: Mannequin#setDescription(String) and #setHideDescription(boolean). */
    private final Method spigotSetDescription;
    private final Method spigotHideDescription;
    /** Paper 1.21.8 and newer: Mob#setDespawnInPeacefulOverride(TriState.FALSE) keeps a monster in a peaceful world. */
    private final Method despawnInPeaceful;
    private final Object triStateFalse;

    @SuppressWarnings("deprecation")
    DummyBody(Logger logger, NamespacedKey mark, MaxHealth maxHealth) {
        this.logger = logger;
        this.mark = mark;
        this.maxHealth = maxHealth;
        Class<? extends Entity> mannequinClass = entityClass("org.bukkit.entity.Mannequin");
        Class<?> resolvable = anyClass("io.papermc.paper.datacomponent.item.ResolvableProfile");
        Class<?> paperProfileClass = anyClass("com.destroystokyo.paper.profile.PlayerProfile");
        Class<?> component = anyClass("net.kyori.adventure.text.Component");
        Class<?> legacy = anyClass("net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer");
        this.mannequin = mannequinClass;
        this.paperSetProfile = method(mannequinClass, "setProfile", resolvable);
        this.paperResolvable = method(resolvable, "resolvableProfile", paperProfileClass);
        this.paperProfile = paperProfileClass;
        this.spigotSetProfile = method(mannequinClass, "setPlayerProfile", PlayerProfile.class);
        this.paperSetDescription = method(mannequinClass, "setDescription", component);
        Method legacySection = method(legacy, "legacySection");
        this.legacySerializer = invokeStatic(legacySection);
        this.legacyDeserialize = method(legacy, "deserialize", String.class);
        this.spigotSetDescription = method(mannequinClass, "setDescription", String.class);
        this.spigotHideDescription = method(mannequinClass, "setHideDescription", boolean.class);
        Class<?> triState = anyClass("net.kyori.adventure.util.TriState");
        this.despawnInPeaceful = method(Mob.class, "setDespawnInPeacefulOverride", triState);
        this.triStateFalse = constant(triState, "FALSE");
    }

    /** Whether this server can show a combat logger as a mannequin wearing their own skin. */
    boolean mannequins() {
        return mannequin != null && (paperSetProfile != null && paperResolvable != null || spigotSetProfile != null);
    }

    /**
     * Spawns the dummy at the snapshot's location, on the calling thread (the quitting player's own, which owns that spot).
     *
     * @return what stands there now, or null when nothing could be spawned (the reason is logged)
     */
    Spawned spawn(Player owner, Snapshot snapshot, Settings settings, String name, String description) {
        Location at = snapshot.location();
        World world = at.getWorld();
        if (world == null) {
            return null;
        }
        if (settings.useMannequin() && mannequins()) {
            try {
                Entity body = world.spawn(at, mannequin, false, entity -> {
                    prepare(entity, snapshot, name, false);
                    wearProfile(entity, owner);
                    describe(entity, description);
                    if (entity instanceof LivingEntity living) {
                        wear(living, snapshot, null);
                    }
                });
                if (body.isValid()) {
                    return new Spawned(body, "mannequin");
                }
                logger.warning("The mannequin for " + snapshot.name() + " did not stay in the world (another plugin removed it?); using "
                    + settings.fallbackEntity().name() + " instead.");
            } catch (RuntimeException | LinkageError failed) {
                if (warnedMannequin.compareAndSet(false, true)) {
                    logger.warning("Could not spawn a mannequin (" + failed + "); combat-log dummies use "
                        + settings.fallbackEntity().name() + " instead.");
                }
            }
        }
        EntityType type = fallbackFor(settings, world.getDifficulty());
        Class<? extends Entity> fallback = type.getEntityClass();
        if (fallback == null) {
            return null;
        }
        try {
            Entity body = world.spawn(at, fallback, false, entity -> {
                prepare(entity, snapshot, name, true);
                if (entity instanceof LivingEntity living) {
                    wear(living, snapshot, headOf(owner));
                }
            });
            if (body.isValid()) {
                return new Spawned(body, type.name().toLowerCase(java.util.Locale.ROOT));
            }
            logger.warning("The " + type.name() + " dummy for " + snapshot.name() + " at " + where(at)
                + " was removed as it spawned (another plugin, or a " + type.name() + " cannot stay in this world).");
        } catch (RuntimeException | LinkageError failed) {
            logger.warning("Could not spawn a " + type.name() + " dummy for " + snapshot.name() + " at " + where(at) + ": " + failed);
        }
        return null;
    }

    /**
     * The fallback mob for a world. A peaceful world removes monsters (a husk, a zombie) the moment they spawn; where the server
     * cannot stop that (before Paper 1.21.8), the peaceful fallback stands in.
     */
    EntityType fallbackFor(Settings settings, Difficulty difficulty) {
        EntityType type = settings.fallbackEntity();
        if (difficulty == Difficulty.PEACEFUL && despawnsInPeaceful(type) && !canStayInPeaceful()) {
            return settings.peacefulFallbackEntity();
        }
        return type;
    }

    boolean canStayInPeaceful() {
        return despawnInPeaceful != null && triStateFalse != null;
    }

    static boolean despawnsInPeaceful(EntityType type) {
        Class<?> kind = type.getEntityClass();
        return kind != null && (Monster.class.isAssignableFrom(kind) || Slime.class.isAssignableFrom(kind) || Ghast.class.isAssignableFrom(kind)
            || Phantom.class.isAssignableFrom(kind));
    }

    /** Marks, names and sets up the entity before it enters the world. */
    @SuppressWarnings("deprecation")
    private void prepare(Entity entity, Snapshot snapshot, String name, boolean fallback) {
        entity.getPersistentDataContainer().set(mark, PersistentDataType.STRING, snapshot.id().toString());
        entity.setPersistent(false);
        entity.setSilent(true);
        if (!name.isEmpty()) {
            entity.setCustomName(name);
            entity.setCustomNameVisible(true);
        }
        if (entity instanceof LivingEntity living) {
            double max = snapshot.maxHealth() > 0 ? snapshot.maxHealth() : 20;
            maxHealth.set(living, max);
            living.setHealth(Math.max(0.5, Math.min(snapshot.health(), maxHealth.of(living))));
            bestEffort(() -> living.setRemoveWhenFarAway(false));
            bestEffort(() -> living.setCanPickupItems(false));
        }
        if (entity instanceof Mob mob) {
            mob.setAware(false);
            bestEffort(() -> mob.setLootTable(null));
            if (fallback && canStayInPeaceful()) {
                bestEffort(() -> invoke(despawnInPeaceful, mob, triStateFalse));
            }
        }
    }

    /** The player's armour and hands; on a mob they never drop (every drop chance is 0 and the death's drops are cleared). */
    private static void wear(LivingEntity body, Snapshot snapshot, ItemStack head) {
        EntityEquipment equipment = body.getEquipment();
        if (equipment == null) {
            return;
        }
        equipment.setBoots(snapshot.armor(0));
        equipment.setLeggings(snapshot.armor(1));
        equipment.setChestplate(snapshot.armor(2));
        equipment.setHelmet(head != null ? head : snapshot.armor(3));
        equipment.setItemInMainHand(Snapshot.copy(snapshot.mainHand()));
        equipment.setItemInOffHand(Snapshot.copy(snapshot.offHand()));
        if (body instanceof Mob) {
            equipment.setBootsDropChance(0f);
            equipment.setLeggingsDropChance(0f);
            equipment.setChestplateDropChance(0f);
            equipment.setHelmetDropChance(0f);
            equipment.setItemInMainHandDropChance(0f);
            equipment.setItemInOffHandDropChance(0f);
        }
    }

    /**
     * The player's head, but only when the server already holds their skin: a head whose profile has no textures makes the
     * server ask Mojang for them every time the item is written (in offline mode, or with a skin not yet loaded).
     */
    @SuppressWarnings("deprecation")
    private static ItemStack headOf(Player owner) {
        try {
            PlayerProfile profile = profileOf(owner);
            if (profile == null || profile.getTextures().isEmpty()) {
                return null;
            }
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            ItemMeta meta = head.getItemMeta();
            if (!(meta instanceof SkullMeta skull)) {
                return null;
            }
            skull.setOwnerProfile(profile);
            head.setItemMeta(skull);
            return head;
        } catch (RuntimeException | LinkageError noHead) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private void wearProfile(Entity body, Player owner) {
        PlayerProfile profile = profileOf(owner);
        if (profile == null) {
            throw new IllegalStateException("the player has no profile");
        }
        if (paperSetProfile != null && paperResolvable != null && paperProfile != null && paperProfile.isInstance(profile)) {
            invoke(paperSetProfile, body, invokeStatic(paperResolvable, profile));
        } else if (spigotSetProfile != null) {
            invoke(spigotSetProfile, body, profile);
        } else {
            throw new IllegalStateException("no way to give a mannequin a profile here");
        }
    }

    /** The line under the mannequin's name ("NPC" by default); "" hides it. */
    private void describe(Entity body, String description) {
        boolean hide = description.isEmpty();
        if (paperSetDescription != null) {
            Object text = null;
            if (!hide && legacySerializer != null && legacyDeserialize != null) {
                text = invoke(legacyDeserialize, legacySerializer, description);
            }
            if (hide || text != null) {
                invoke(paperSetDescription, body, text);
            }
        } else if (spigotSetDescription != null && spigotHideDescription != null) {
            if (hide) {
                invoke(spigotHideDescription, body, true);
            } else {
                invoke(spigotSetDescription, body, description);
            }
        }
    }

    /**
     * The player's profile. OfflinePlayer#getPlayerProfile returns Paper's own profile type on Paper and Bukkit's on Spigot
     * (two different method descriptors), so it is called by name; Paper's type extends Bukkit's.
     */
    @SuppressWarnings("deprecation")
    private static PlayerProfile profileOf(Player owner) {
        Object profile = GET_PROFILE == null ? null : invoke(GET_PROFILE, owner);
        return profile instanceof PlayerProfile bukkit ? bukkit : null;
    }

    private static final Method GET_PROFILE = method(OfflinePlayer.class, "getPlayerProfile");

    /** For calls some servers or test servers do not implement; the dummy works without them. */
    private static void bestEffort(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException | LinkageError unsupported) {
            // not on this server
        }
    }

    private static String where(Location at) {
        return (at.getWorld() == null ? "?" : at.getWorld().getName()) + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ();
    }

    // --- reflection ---

    private static Class<? extends Entity> entityClass(String name) {
        Class<?> found = anyClass(name);
        return found != null && Entity.class.isAssignableFrom(found) ? found.asSubclass(Entity.class) : null;
    }

    private static Class<?> anyClass(String name) {
        try {
            return Class.forName(name, false, DummyBody.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        if (owner == null) {
            return null;
        }
        for (Class<?> parameter : parameters) {
            if (parameter == null) {
                return null;
            }
        }
        try {
            return owner.getMethod(name, parameters);
        } catch (NoSuchMethodException | LinkageError | SecurityException absent) {
            return null;
        }
    }

    private static Object constant(Class<?> owner, String field) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getField(field).get(null);
        } catch (ReflectiveOperationException | LinkageError | SecurityException absent) {
            return null;
        }
    }

    private static Object invokeStatic(Method method, Object... args) {
        if (method == null) {
            return null;
        }
        return invoke(method, null, args);
    }

    private static Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException denied) {
            throw new IllegalStateException(denied);
        }
    }

    /** The owner UUID a body carries, or null for any other entity. */
    UUID ownerOf(Entity entity) {
        String value = entity.getPersistentDataContainer().get(mark, PersistentDataType.STRING);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notOurs) {
            return null;
        }
    }

    boolean isDummy(Entity entity) {
        return entity.getPersistentDataContainer().has(mark, PersistentDataType.STRING);
    }

    /** For status: how combat loggers are shown on this server. */
    String describeKind(Settings settings, World world) {
        if (settings.useMannequin() && mannequins()) {
            return "mannequin";
        }
        Difficulty difficulty = world == null ? Difficulty.NORMAL : world.getDifficulty();
        return fallbackFor(settings, difficulty).name().toLowerCase(java.util.Locale.ROOT);
    }
}

package com.nyr.guardian.combattag;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Keyed;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.potion.PotionType;

/** Who is behind a hit, and which potion effects count as an attack. */
final class Attackers {

    /** The effects the game itself files as harmful, by key; the same on every version this plugin runs on. */
    private static final Set<String> HARMFUL = Set.of("slowness", "mining_fatigue", "instant_damage", "nausea", "blindness", "hunger",
        "weakness", "poison", "wither", "levitation", "unluck", "darkness", "wind_charged", "weaving", "oozing", "infested");

    private Attackers() {
    }

    /**
     * The player behind a hit: the player, the shooter of a projectile (arrow, trident, snowball, egg, fireball, wind charge,
     * thrown potion...), whoever lit primed TNT, or whoever threw the potion that made an effect cloud. Null for anything else.
     */
    static Player player(Entity damager) {
        Entity source = source(damager);
        return source instanceof Player player ? player : null;
    }

    /** The mob behind a hit (a zombie's punch, a skeleton's arrow), or null. */
    static Mob mob(Entity damager) {
        Entity source = source(damager);
        return source instanceof Mob mob ? mob : null;
    }

    private static Entity source(Entity damager) {
        if (damager instanceof Projectile projectile) {
            return projectile.getShooter() instanceof Entity shooter ? shooter : null;
        }
        if (damager instanceof TNTPrimed tnt) {
            return tnt.getSource();
        }
        if (damager instanceof AreaEffectCloud cloud) {
            return cloud.getSource() instanceof Entity thrower ? thrower : null;
        }
        return damager;
    }

    static boolean harmful(Collection<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (effect != null && harmful(effect.getType())) {
                return true;
            }
        }
        return false;
    }

    /** The effects of an effect cloud: any added to it, and its potion's. */
    static boolean harmful(AreaEffectCloud cloud) {
        if (cloud.hasCustomEffects() && harmful(cloud.getCustomEffects())) {
            return true;
        }
        PotionType base = cloud.getBasePotionType();
        return base != null && harmful(base.getPotionEffects());
    }

    @SuppressWarnings("deprecation")
    static boolean harmful(PotionEffectType type) {
        if (type == null) {
            return false;
        }
        Keyed keyed = type;
        if (HARMFUL.contains(keyed.getKey().getKey().toLowerCase(Locale.ROOT))) {
            return true;
        }
        // An effect added by a data pack or a newer game version: ask the server how it files it.
        try {
            return type.getCategory() == PotionEffectTypeCategory.HARMFUL;
        } catch (RuntimeException | LinkageError unknown) {
            return false;
        }
    }
}

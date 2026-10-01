package com.nyr.guardian.combattag;

import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;

/** config.yml as the plugin uses it, read once per start and never changed afterwards. */
record Settings(
    long combatMillis,
    boolean tagOnHarmfulPotions,
    boolean tagOnMobs,
    CommandRules commands,
    boolean dummyEnabled,
    long dummyMillis,
    boolean dummyOnKick,
    boolean useMannequin,
    EntityType fallbackEntity,
    EntityType peacefulFallbackEntity,
    String dummyName,
    String dummyDescription) {

    static final long MAX_SECONDS = 3600;

    static Settings read(ConfigurationSection config, Logger logger) {
        long combat = seconds(config, "combat-seconds", 15, logger);
        String mode = config.getString("blocked-commands.mode", "blacklist").trim().toLowerCase(Locale.ROOT);
        CommandRules.Mode rulesMode;
        if (mode.equals("whitelist")) {
            rulesMode = CommandRules.Mode.WHITELIST;
        } else {
            if (!mode.equals("blacklist")) {
                logger.warning("blocked-commands.mode must be blacklist or whitelist, not \"" + mode + "\"; using blacklist.");
            }
            rulesMode = CommandRules.Mode.BLACKLIST;
        }
        List<String> listed = config.getStringList("blocked-commands.list");
        return new Settings(
            combat * 1000,
            config.getBoolean("tag-on.harmful-potions", true),
            config.getBoolean("tag-on.mobs", false),
            new CommandRules(rulesMode, listed),
            config.getBoolean("dummy.enabled", true),
            seconds(config, "dummy.seconds", 30, logger) * 1000,
            config.getBoolean("dummy.on-kick", false),
            config.getBoolean("dummy.mannequin", true),
            mob(config, "dummy.fallback-entity", EntityType.HUSK, logger),
            mob(config, "dummy.peaceful-fallback-entity", EntityType.VILLAGER, logger),
            config.getString("dummy.name", "&c{player}"),
            config.getString("dummy.description", "&7Logged out in combat"));
    }

    private static long seconds(ConfigurationSection config, String key, long fallback, Logger logger) {
        long value = config.getLong(key, fallback);
        if (value < 1 || value > MAX_SECONDS) {
            logger.warning(key + " must be between 1 and " + MAX_SECONDS + " seconds, not " + value + "; using " + fallback + ".");
            return fallback;
        }
        return value;
    }

    /** An entity type the game spawns as a mob with health (so its AI can be switched off), or the fallback. */
    static EntityType mob(ConfigurationSection config, String key, EntityType fallback, Logger logger) {
        String name = config.getString(key, fallback.name());
        EntityType type = parse(name);
        if (type == null) {
            logger.warning(key + ": \"" + name + "\" is not a mob this server knows (use a name such as HUSK, ZOMBIE or VILLAGER); using "
                + fallback.name() + ".");
            return fallback;
        }
        return type;
    }

    static EntityType parse(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim().toUpperCase(Locale.ROOT).replace("MINECRAFT:", "").replace(' ', '_');
        for (EntityType type : EntityType.values()) {
            if (!type.name().equals(wanted)) {
                continue;
            }
            Class<?> entityClass = type.getEntityClass();
            return entityClass != null && type.isSpawnable() && Mob.class.isAssignableFrom(entityClass) ? type : null;
        }
        return null;
    }

    int combatSeconds() {
        return (int) (combatMillis / 1000);
    }

    int dummySeconds() {
        return (int) (dummyMillis / 1000);
    }
}

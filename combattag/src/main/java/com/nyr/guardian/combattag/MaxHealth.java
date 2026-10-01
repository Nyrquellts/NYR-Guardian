package com.nyr.guardian.combattag;

import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;

/**
 * The max-health attribute, found by its registry key: "max_health" from 1.21.3 on, "generic.max_health" before. The Java
 * constant changed name and Attribute turned from an enum into an interface, so no constant is named in the code.
 */
final class MaxHealth {

    private final Attribute attribute;

    MaxHealth() {
        this.attribute = find();
    }

    private static Attribute find() {
        for (String key : new String[] {"max_health", "generic.max_health"}) {
            try {
                Attribute found = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(key));
                if (found != null) {
                    return found;
                }
            } catch (RuntimeException notThere) {
                // an older registry that refuses the newer key: try the next one
            }
        }
        return null;
    }

    boolean known() {
        return attribute != null;
    }

    /** The entity's max health with every modifier (health boost included); its current health if unknown. */
    @SuppressWarnings("deprecation")
    double of(LivingEntity entity) {
        AttributeInstance instance = attribute == null ? null : entity.getAttribute(attribute);
        return instance != null ? instance.getValue() : entity.getMaxHealth();
    }

    /** Sets the entity's base max health; false when the entity has no such attribute. */
    boolean set(LivingEntity entity, double value) {
        AttributeInstance instance = attribute == null ? null : entity.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        instance.setBaseValue(value);
        return true;
    }
}

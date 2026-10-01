package com.nyr.guardian.common;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/** Which worlds a plugin runs in: an enabled list ("*" for every world) minus a disabled list. Names ignore case. */
public final class WorldFilter {

    private final boolean everyWorld;
    private final Set<String> enabled;
    private final Set<String> disabled;

    private WorldFilter(List<String> enabled, List<String> disabled) {
        this.enabled = lower(enabled);
        this.disabled = lower(disabled);
        this.everyWorld = this.enabled.contains("*");
    }

    public static WorldFilter everywhere() {
        return new WorldFilter(List.of("*"), List.of());
    }

    /** Reads enabled and disabled from the given section; a missing section means every world. */
    public static WorldFilter from(ConfigurationSection section) {
        if (section == null) {
            return everywhere();
        }
        List<String> enabled = section.isList("enabled") ? section.getStringList("enabled") : List.of("*");
        return new WorldFilter(enabled, section.getStringList("disabled"));
    }

    public boolean allows(String worldName) {
        String name = worldName == null ? "" : worldName.toLowerCase(Locale.ROOT);
        if (disabled.contains(name)) {
            return false;
        }
        return everyWorld || enabled.contains(name);
    }

    private static Set<String> lower(List<String> names) {
        Set<String> out = new HashSet<>();
        for (String name : names) {
            if (name != null) {
                out.add(name.trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }
}

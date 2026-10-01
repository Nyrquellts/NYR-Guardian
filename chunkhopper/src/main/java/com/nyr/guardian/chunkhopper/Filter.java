package com.nyr.guardian.chunkhopper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;

/**
 * What a Chunk Hopper collects: nine slots, each empty or one material. An empty filter collects everything. Immutable, so a
 * record can hand the current filter to any thread and replace it in one write.
 */
final class Filter {

    static final int SLOTS = 9;
    static final Filter EMPTY = new Filter(new Material[SLOTS]);

    private final Material[] slots;
    private final Set<Material> materials;

    private Filter(Material[] slots) {
        this.slots = slots;
        Set<Material> set = new HashSet<>();
        for (Material material : slots) {
            if (material != null) {
                set.add(material);
            }
        }
        this.materials = Set.copyOf(set);
    }

    /** The material in a slot, or null when it is free. */
    Material slot(int slot) {
        return slot >= 0 && slot < SLOTS ? slots[slot] : null;
    }

    boolean isEmpty() {
        return materials.isEmpty();
    }

    boolean contains(Material material) {
        return materials.contains(material);
    }

    /** Whether a drop of this material is collected: always with an empty filter, otherwise only when it is listed. */
    boolean accepts(Material material) {
        return materials.isEmpty() || materials.contains(material);
    }

    int size() {
        return materials.size();
    }

    /** This filter with {@code slot} set to {@code material}; a slot already holding it elsewhere is freed (it moves). */
    Filter with(int slot, Material material) {
        if (slot < 0 || slot >= SLOTS || material == null || material.isAir()) {
            return this;
        }
        Material[] next = slots.clone();
        for (int i = 0; i < SLOTS; i++) {
            if (next[i] == material) {
                next[i] = null;
            }
        }
        next[slot] = material;
        return new Filter(next);
    }

    /** This filter with {@code slot} free. */
    Filter without(int slot) {
        if (slot < 0 || slot >= SLOTS || slots[slot] == null) {
            return this;
        }
        Material[] next = slots.clone();
        next[slot] = null;
        return new Filter(next);
    }

    /** This filter with {@code material} in its first free slot; itself when already listed; null when all slots are taken. */
    Filter adding(Material material) {
        if (material == null || material.isAir() || materials.contains(material)) {
            return this;
        }
        for (int i = 0; i < SLOTS; i++) {
            if (slots[i] == null) {
                return with(i, material);
            }
        }
        return null;
    }

    /** "minecraft:cactus,,minecraft:bone,,,,,," : one field per slot, as the TileState stores it. */
    String serialize() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < SLOTS; i++) {
            if (i > 0) {
                out.append(',');
            }
            if (slots[i] != null) {
                out.append(slots[i].getKey());
            }
        }
        return out.toString();
    }

    /** Reads {@link #serialize()}'s form; unknown materials (from a newer server version) leave their slot free. */
    static Filter parse(String stored) {
        if (stored == null || stored.isBlank()) {
            return EMPTY;
        }
        String[] fields = stored.split(",", -1);
        Material[] slots = new Material[SLOTS];
        for (int i = 0; i < Math.min(SLOTS, fields.length); i++) {
            String field = fields[i].trim();
            if (!field.isEmpty()) {
                Material material = Material.matchMaterial(field);
                if (material != null && !material.isAir()) {
                    slots[i] = material;
                }
            }
        }
        return new Filter(slots);
    }

    /** "cactus, bone" in slot order, or {@code everything} for an empty filter. */
    String describe(String everything) {
        List<String> names = new ArrayList<>();
        for (Material material : slots) {
            if (material != null) {
                names.add(material.getKey().getKey().toLowerCase(Locale.ROOT));
            }
        }
        return names.isEmpty() ? everything : String.join(", ", names);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Filter filter && Arrays.equals(slots, filter.slots);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(slots);
    }

    @Override
    public String toString() {
        return serialize();
    }
}

package com.nyr.guardian.combattag;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * A combat-log dummy that died: whose it was, who or what killed it, where, and whether its owner's items and experience were
 * dropped there. Written to disk before anything drops; read when the owner next joins, even after a restart or a crash.
 */
record KillRecord(UUID owner, String ownerName, String killer, UUID killerId, long time, String world, double x, double y, double z,
                  boolean itemsDropped, boolean experienceDropped, int stacks, int experience) {

    String toYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of(
            "NYR CombatTag Pro: " + ownerName + " logged out in combat and their dummy was killed.",
            "When " + ownerName + " next joins, what the dummy dropped is taken from them and they die; then this file is deleted.",
            "Delete it by hand only to let them keep their items (the dropped copies may already be in someone else's hands)."));
        yaml.set("owner", owner.toString());
        yaml.set("owner-name", ownerName);
        yaml.set("killer", killer);
        yaml.set("killer-uuid", killerId == null ? "" : killerId.toString());
        yaml.set("time", Instant.ofEpochMilli(time).toString());
        yaml.set("world", world);
        yaml.set("x", round(x));
        yaml.set("y", round(y));
        yaml.set("z", round(z));
        yaml.set("items-dropped", itemsDropped);
        yaml.set("experience-dropped", experienceDropped);
        yaml.set("stacks", stacks);
        yaml.set("experience", experience);
        return yaml.saveToString();
    }

    static KillRecord fromYaml(String text) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(text);
        } catch (InvalidConfigurationException broken) {
            throw new IOException("not a kill record: " + broken.getMessage(), broken);
        }
        String owner = yaml.getString("owner");
        if (owner == null) {
            throw new IOException("not a kill record: no owner");
        }
        String killerId = yaml.getString("killer-uuid", "");
        long time;
        try {
            time = Instant.parse(yaml.getString("time", "1970-01-01T00:00:00Z")).toEpochMilli();
        } catch (RuntimeException badTime) {
            time = 0;
        }
        try {
            return new KillRecord(UUID.fromString(owner), yaml.getString("owner-name", "?"), yaml.getString("killer", "?"),
                killerId == null || killerId.isEmpty() ? null : UUID.fromString(killerId), time, yaml.getString("world", "?"),
                yaml.getDouble("x"), yaml.getDouble("y"), yaml.getDouble("z"),
                // a record that cannot say what dropped is read as "everything dropped": the safe side against duplicates
                yaml.getBoolean("items-dropped", true), yaml.getBoolean("experience-dropped", true),
                yaml.getInt("stacks"), yaml.getInt("experience"));
        } catch (IllegalArgumentException badUuid) {
            throw new IOException("not a kill record: " + badUuid.getMessage(), badUuid);
        }
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }
}

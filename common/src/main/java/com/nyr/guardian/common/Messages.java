package com.nyr.guardian.common;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;

/** Chat lines from the messages section of config.yml; a message set to "" is never sent. */
public final class Messages {

    private volatile ConfigurationSection section;

    public Messages(ConfigurationSection section) {
        this.section = section;
    }

    public void use(ConfigurationSection section) {
        this.section = section;
    }

    /** The coloured message for a key, {prefix} and the given placeholders filled in; "" when it is switched off. */
    public String format(String key, Object... pairs) {
        ConfigurationSection current = section;
        String raw = current == null ? null : current.getString(key);
        if (raw == null) {
            raw = "&c[missing message: " + key + "]";
        }
        if (raw.isEmpty()) {
            return "";
        }
        String prefix = current == null ? "" : current.getString("prefix", "");
        return Text.color(Text.fill(raw.replace("{prefix}", prefix == null ? "" : prefix), pairs));
    }

    public void send(CommandSender to, String key, Object... pairs) {
        String message = format(key, pairs);
        if (!message.isEmpty()) {
            to.sendMessage(message);
        }
    }
}

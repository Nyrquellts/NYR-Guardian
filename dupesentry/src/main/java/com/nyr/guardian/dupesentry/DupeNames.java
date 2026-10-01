package com.nyr.guardian.dupesentry;

import com.nyr.guardian.common.Messages;
import java.util.Locale;
import org.bukkit.Material;

/** What a stopped dupe is called in staff alerts, from the messages section of config.yml. */
final class DupeNames {

    private final Messages messages;

    DupeNames(Messages messages) {
        this.messages = messages;
    }

    String tnt() {
        return messages.format("dupe-tnt");
    }

    String pushedBlock(Material material) {
        return messages.format("dupe-pushed-block", "item", readable(material));
    }

    String endPortal(Material material) {
        return messages.format("dupe-end-portal", "item", readable(material));
    }

    String tripwireHook() {
        return messages.format("dupe-tripwire-hook");
    }

    String container() {
        return messages.format("dupe-container");
    }

    /** WHITE_CARPET becomes "white carpet". */
    static String readable(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}

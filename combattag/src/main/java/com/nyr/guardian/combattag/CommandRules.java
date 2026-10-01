package com.nyr.guardian.combattag;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Which commands a player in combat may not run: a blacklist, or a whitelist of the only ones allowed. */
final class CommandRules {

    enum Mode { BLACKLIST, WHITELIST }

    /** This plugin's own command and aliases stay usable in combat whatever the list says, so a player can see their timer. */
    private static final Set<String> ALWAYS_ALLOWED = Set.of("combattag", "ct", "combat");

    private final Mode mode;
    private final Set<String> listed;

    CommandRules(Mode mode, Collection<String> listed) {
        this.mode = mode;
        this.listed = new HashSet<>();
        for (String command : listed) {
            String root = root(command);
            if (!root.isEmpty()) {
                this.listed.add(root);
            }
        }
    }

    /**
     * The command a chat line runs: its first word without the leading "/" and without a "plugin:" namespace, in lower case.
     * "/Essentials:home base" gives "home".
     */
    static String root(String line) {
        if (line == null) {
            return "";
        }
        String text = line.strip();
        if (text.startsWith("/")) {
            text = text.substring(1);
        }
        int space = text.indexOf(' ');
        String first = space < 0 ? text : text.substring(0, space);
        int colon = first.indexOf(':');
        if (colon >= 0) {
            first = first.substring(colon + 1);
        }
        return first.toLowerCase(Locale.ROOT);
    }

    boolean blocks(String root) {
        if (root.isEmpty() || ALWAYS_ALLOWED.contains(root)) {
            return false;
        }
        return mode == Mode.BLACKLIST ? listed.contains(root) : !listed.contains(root);
    }

    Mode mode() {
        return mode;
    }

    int size() {
        return listed.size();
    }
}

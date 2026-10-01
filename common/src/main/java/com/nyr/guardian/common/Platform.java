package com.nyr.guardian.common;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The server software and Minecraft version a plugin runs on, read once at start. */
public final class Platform {

    public enum Software {
        FOLIA("Folia"), PURPUR("Purpur"), PAPER("Paper"), SPIGOT("Spigot"), CRAFTBUKKIT("CraftBukkit");

        private final String display;

        Software(String display) {
            this.display = display;
        }

        public String display() {
            return display;
        }
    }

    private static final Pattern MC = Pattern.compile("\\(MC: ([0-9]+(?:\\.[0-9]+)*)");
    private static final Pattern LEADING = Pattern.compile("^([0-9]+(?:\\.[0-9]+)*)");

    private final Software software;
    private final List<Integer> version;

    public Platform(Software software, List<Integer> version) {
        this.software = software;
        this.version = List.copyOf(version);
    }

    /** {@code serverVersion} is Server#getVersion, {@code bukkitVersion} Server#getBukkitVersion. */
    public static Platform detect(String serverVersion, String bukkitVersion) {
        return new Platform(detectSoftware(), parseVersion(serverVersion, bukkitVersion));
    }

    static Software detectSoftware() {
        if (present("io.papermc.paper.threadedregions.RegionizedServer")) {
            return Software.FOLIA;
        }
        if (present("org.purpurmc.purpur.PurpurConfig")) {
            return Software.PURPUR;
        }
        if (present("io.papermc.paper.configuration.Configuration") || present("com.destroystokyo.paper.PaperConfig")) {
            return Software.PAPER;
        }
        if (present("org.spigotmc.SpigotConfig")) {
            return Software.SPIGOT;
        }
        return Software.CRAFTBUKKIT;
    }

    /** "git-Paper-123 (MC: 1.21.11)" gives 1.21.11; year versions such as 26.1.2 parse the same way. Unknown gives 0. */
    static List<Integer> parseVersion(String serverVersion, String bukkitVersion) {
        if (serverVersion != null) {
            Matcher mc = MC.matcher(serverVersion);
            if (mc.find()) {
                return numbers(mc.group(1));
            }
        }
        if (bukkitVersion != null) {
            Matcher leading = LEADING.matcher(bukkitVersion.trim());
            if (leading.find()) {
                return numbers(leading.group(1));
            }
        }
        return List.of(0);
    }

    private static List<Integer> numbers(String dotted) {
        List<Integer> parts = new ArrayList<>();
        for (String part : dotted.split("\\.")) {
            try {
                parts.add(Integer.parseInt(part));
            } catch (NumberFormatException tooLong) {
                parts.add(0);
            }
        }
        return parts;
    }

    private static boolean present(String className) {
        try {
            Class.forName(className, false, Platform.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    public Software software() {
        return software;
    }

    public boolean folia() {
        return software == Software.FOLIA;
    }

    /** Paper or anything built on it (Purpur, Folia). */
    public boolean paperBased() {
        return software == Software.PAPER || software == Software.PURPUR || software == Software.FOLIA;
    }

    public List<Integer> version() {
        return version;
    }

    /** True when the Minecraft version is at least the one given, compared part by part. */
    public boolean atLeast(int... wanted) {
        for (int i = 0; i < wanted.length; i++) {
            int have = i < version.size() ? version.get(i) : 0;
            if (have != wanted[i]) {
                return have > wanted[i];
            }
        }
        return true;
    }

    public String versionString() {
        StringBuilder out = new StringBuilder();
        for (int part : version) {
            if (!out.isEmpty()) {
                out.append('.');
            }
            out.append(part);
        }
        return out.toString();
    }

    public String describe() {
        return software.display() + " " + versionString();
    }
}

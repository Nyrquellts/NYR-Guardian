package com.nyr.guardian.dupesentry;

/** DupeSentry's guards: their config.yml section and the Paper setting that, left false, already blocks the same dupe. */
public enum Guard {
    PISTON_DUPES("piston-dupes", "allowPistonDuplication", "allow-piston-duplication"),
    PORTAL_GRAVITY("portal-gravity", "allowUnsafeEndPortalTeleportation", "allow-unsafe-end-portal-teleportation"),
    TRIPWIRE_HOOKS("tripwire-hooks", "skipTripwireHookPlacementValidation", "skip-tripwire-hook-placement-validation"),
    CONTAINER_DESYNC("container-desync", null, null);

    private final String key;
    private final String paperField;
    private final String paperSetting;

    Guard(String key, String paperField, String paperSetting) {
        this.key = key;
        this.paperField = paperField;
        this.paperSetting = paperSetting;
    }

    /** The section under guards: in config.yml. */
    public String key() {
        return key;
    }

    /** The field of Paper's GlobalConfiguration.UnsupportedSettings, or null when Paper has no fix for this. */
    String paperField() {
        return paperField;
    }

    /** The key under unsupported-settings: in config/paper-global.yml, or null. */
    public String paperSetting() {
        return paperSetting;
    }
}

package com.nyr.guardian.dupesentry;

import com.nyr.guardian.common.GuardianPlugin;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import org.bukkit.Location;

/**
 * NYR DupeSentry. Stops the mechanical dupes Paper blocks by default and Spigot does not (piston TNT, carpet and rail dupers,
 * falling blocks through end portals, tripwire hook duplicators) and closes container screens whose container is gone.
 */
public class DupeSentryPlugin extends GuardianPlugin {

    private ServerFixes fixes;
    private ServerFixes fixesForTests;
    private Reporter reporter;
    private TickMemory pushed;
    private TickMemory hooks;
    private ContainerGuard containers;

    @Override
    public String pluginId() {
        return "dupesentry";
    }

    @Override
    public String displayName() {
        return "NYR DupeSentry";
    }

    @Override
    protected String commandName() {
        return "dupesentry";
    }

    @Override
    protected void startPlugin() {
        // Paper's unsupported-settings take effect as soon as Paper reloads them, so they are read again on every start.
        fixes = fixesForTests != null ? fixesForTests : ServerFixes.detect(platform(), new File("config", "paper-global.yml"));
        if (reporter == null) {
            reporter = new Reporter(alerts(), messages());
        }
        reporter.alertContainers(guardSetting(Guard.CONTAINER_DESYNC, "alert", false));
        DupeNames names = new DupeNames(messages());
        BiConsumer<Location, Runnable> nextTick = (location, task) -> scheduler().runAtLocationLater(location, task, 1L);
        pushed = new TickMemory(nextTick);
        hooks = new TickMemory(nextTick);

        if (active(Guard.PISTON_DUPES)) {
            boolean tnt = guardSetting(Guard.PISTON_DUPES, "tnt", true);
            boolean blocks = guardSetting(Guard.PISTON_DUPES, "carpets-and-rails", true);
            if (tnt || blocks) {
                listen(new PistonGuard(pushed, worlds(), reporter, names, tnt, blocks));
            }
        }
        if (active(Guard.PORTAL_GRAVITY)) {
            listen(new PortalGuard(worlds(), reporter, names, guardSetting(Guard.PORTAL_GRAVITY, "nether-portals", false)));
        }
        if (active(Guard.TRIPWIRE_HOOKS)) {
            listen(new TripwireGuard(hooks, worlds(), reporter, names, getServer()::getWorld, TripwireGuard::supported));
        }
        if (enabled(Guard.CONTAINER_DESYNC)) {
            containers = new ContainerGuard(getServer(), scheduler(), worlds(), reporter, names,
                guardSetting(Guard.CONTAINER_DESYNC, "cursor-item", true));
            listen(containers);
        }
        List<String> summary = new ArrayList<>();
        for (Guard guard : Guard.values()) {
            summary.add(guard.key() + " " + plainState(guard));
        }
        getLogger().info("Guards: " + String.join(", ", summary));
    }

    @Override
    protected void stopPlugin() {
        if (pushed != null) {
            pushed.clear();
        }
        if (hooks != null) {
            hooks.clear();
        }
        if (containers != null) {
            containers.clear();
            containers = null;
        }
    }

    @Override
    protected void status(List<String> lines) {
        for (Guard guard : Guard.values()) {
            String key = guard == Guard.CONTAINER_DESYNC ? "status-container" : "status-guard";
            lines.add(messages().format(key, "guard", guard.key(), "state", state(guard), "count", reporter == null ? 0 : reporter.count(guard)));
        }
    }

    private boolean enabled(Guard guard) {
        return guardSetting(guard, "enabled", true);
    }

    private boolean active(Guard guard) {
        return enabled(guard) && (guard.paperSetting() == null || fixes.needed(guard));
    }

    private boolean guardSetting(Guard guard, String name, boolean fallback) {
        return settings().getBoolean("guards." + guard.key() + "." + name, fallback);
    }

    /** The coloured state of one guard for /dupesentry status. */
    private String state(Guard guard) {
        if (!enabled(guard)) {
            return messages().format("guard-off");
        }
        if (guard.paperSetting() != null && !fixes.needed(guard)) {
            String key = fixes.verdict(guard) == ServerFixes.Verdict.BLOCKS ? "guard-server-blocks" : "guard-server-unknown";
            return messages().format(key, "server", fixes.server(), "setting", "unsupported-settings." + guard.paperSetting());
        }
        List<String> parts = new ArrayList<>();
        if (guard == Guard.PISTON_DUPES) {
            if (guardSetting(guard, "tnt", true)) {
                parts.add(messages().format("part-tnt"));
            }
            if (guardSetting(guard, "carpets-and-rails", true)) {
                parts.add(messages().format("part-carpets-and-rails"));
            }
            if (parts.isEmpty()) {
                return messages().format("guard-off");
            }
        } else if (guard == Guard.PORTAL_GRAVITY && guardSetting(guard, "nether-portals", false)) {
            parts.add(messages().format("part-nether"));
        }
        return parts.isEmpty() ? messages().format("guard-on") : messages().format("guard-on-parts", "parts", String.join(", ", parts));
    }

    /** The state for the start-up log line: on, off or idle and why. */
    private String plainState(Guard guard) {
        if (!enabled(guard)) {
            return "off";
        }
        if (guard.paperSetting() != null && !fixes.needed(guard)) {
            return fixes.verdict(guard) == ServerFixes.Verdict.BLOCKS
                ? "idle (" + fixes.server() + " blocks it)"
                : "idle (" + guard.paperSetting() + " unreadable)";
        }
        return "on";
    }

    // --- for the tests in this package -----------------------------------------------------------------------------------

    /** Makes the next start use these server fixes instead of detecting them (MockBukkit is no Paper server). */
    void useServerFixes(ServerFixes fixes) {
        this.fixesForTests = fixes;
    }

    ServerFixes serverFixes() {
        return fixes;
    }

    long stopped(Guard guard) {
        return reporter == null ? 0 : reporter.count(guard);
    }

    TickMemory pushedMemory() {
        return pushed;
    }

    TickMemory hookMemory() {
        return hooks;
    }

    ContainerGuard containerGuard() {
        return containers;
    }
}

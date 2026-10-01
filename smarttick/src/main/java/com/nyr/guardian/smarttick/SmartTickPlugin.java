package com.nyr.guardian.smarttick;

import com.nyr.guardian.common.GuardianPlugin;
import com.nyr.guardian.common.NyrCommand;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;

/**
 * NYR SmartTick. When the server falls behind, trading-hall villagers' brains sleep ({@code Mob#setAware(false)}): they stop
 * pathfinding, gossiping and looking for work, and players still trade with them. When the server recovers they wake.
 * Nothing is ever removed.
 */
public class SmartTickPlugin extends GuardianPlugin {

    private static final long PROBLEM_LOG_GAP_MILLIS = 60_000;

    // Kept across reloads: the load signal, the server's behind/keeping-up state, what is known about each villager, totals.
    private final Engine.Totals totals = new Engine.Totals();
    private final Tracker tracker = new Tracker();
    private Load load;
    private Hysteresis serverState;
    private Restocker restocker;
    private volatile Engine engine;
    private volatile long lastProblemLogged;

    @Override
    public String pluginId() {
        return "smarttick";
    }

    @Override
    public String displayName() {
        return "NYR SmartTick";
    }

    @Override
    protected String commandName() {
        return "smarttick";
    }

    @Override
    protected void startPlugin() {
        Settings settings = Settings.from(settings());
        for (String problem : settings.problems()) {
            getLogger().warning("config.yml: " + problem);
        }
        if (load == null) {
            load = Loads.pick(getServer(), platform().folia(), getLogger());
            serverState = new Hysteresis(false);
            restocker = new Restocker(Villager.class);
            getLogger().info("Reading the load from " + load.source() + "; sleeping villagers are restocked "
                + (restocker.serverRestock() ? "by the server's own restock" : "trade by trade") + ".");
            noteInactiveVillagerTicks();
        }
        load.restart();
        serverState.forgetRelapses();
        tracker.forgetRelapses();
        Eligibility eligibility = new Eligibility(worlds(), settings.skipVillagersWithBeds(), settings.stationaryMillis());
        Engine started = new Engine(this, settings, load, serverState, tracker, totals, eligibility, restocker,
            () -> System.nanoTime() / 1_000_000, !platform().folia());
        engine = started;
        listen(new VillagerListener(started, totals));
        scheduler().runTimer(started::tick, 1L, 1L);
    }

    /**
     * Paper, Folia and Spigot tick villagers outside the entity activation range (and on Spigot one tick in four near players)
     * through their "inactive" path, and with spigot.yml's tick-inactive-villagers on (the default) that path runs the brain
     * without asking whether the villager is aware. Sleep cannot pause those ticks, so the owner is told once at start.
     */
    private void noteInactiveVillagerTicks() {
        boolean inactiveBrains;
        try {
            inactiveBrains = getServer().spigot().getConfig()
                .getBoolean("world-settings.default.entity-activation-range.tick-inactive-villagers", true);
        } catch (RuntimeException noSpigotSettings) {
            return;
        }
        if (inactiveBrains) {
            getLogger().info("spigot.yml has tick-inactive-villagers: true, so the server still runs the brains of villagers it ticks as "
                + "inactive (beyond the entity activation range" + (platform().paperBased() ? "" : ", and one tick in four near players")
                + "), asleep or not. Set it to false for SmartTick to pause sleeping villagers fully.");
        }
    }

    @Override
    protected void stopPlugin() {
        Engine stopping = engine;
        engine = null;
        if (stopping == null) {
            return;
        }
        stopping.stop();
        boolean disabling = !isEnabled();
        if (!disabling && staysOnAfterReload()) {
            // A reload: the next start takes the sleeping villagers over where they are.
            return;
        }
        wakeLoaded(disabling, stopping);
    }

    /** Whether config.yml, as it will be read after this reload, keeps the plugin on. A file that does not parse runs on defaults. */
    private boolean staysOnAfterReload() {
        YamlConfiguration next = new YamlConfiguration();
        try {
            next.load(new File(getDataFolder(), "config.yml"));
        } catch (IOException | InvalidConfigurationException unreadable) {
            return true;
        }
        return next.getBoolean("enabled", true);
    }

    /**
     * Wakes every sleeping villager in a loaded chunk. Paper and Spigot do it at once. Folia can only touch an entity on its own
     * region's thread: switched off by a reload it hands each villager a wake task; stopping, it wakes the ones this thread
     * owns, and the rest wake when their chunks next load with SmartTick installed.
     */
    private void wakeLoaded(boolean disabling, Engine stopping) {
        int woken = 0;
        int unreachable = 0;
        for (World world : getServer().getWorlds()) {
            for (Villager villager : world.getEntitiesByClass(Villager.class)) {
                if (!platform().folia()) {
                    if (Marks.asleep(villager)) {
                        Marks.wake(villager);
                        totals.woken.increment();
                        woken++;
                    }
                } else if (!disabling) {
                    scheduler().runAtEntity(villager, task -> {
                        if (Marks.asleep(villager)) {
                            Marks.wake(villager);
                            totals.woken.increment();
                        }
                    });
                } else if (scheduler().isOwnedByCurrentRegion(villager)) {
                    if (Marks.asleep(villager)) {
                        Marks.wake(villager);
                        totals.woken.increment();
                        woken++;
                    }
                } else {
                    unreachable++;
                }
            }
        }
        if (woken > 0) {
            getLogger().info("Woke " + woken + " sleeping villager(s).");
        }
        Engine.Pass pass = stopping.settled();
        if (unreachable > 0 && pass != null && pass.asleep.get() > 0) {
            getLogger().info(unreachable + " villager(s) could not be reached while Folia stops; any saved asleep wakes when its "
                + "chunk loads with SmartTick installed. Run /smarttick wakeall before removing the plugin.");
        }
    }

    @Override
    protected void commands(NyrCommand command) {
        command.byDefault("status");
        command.add("sleep", "", permission("admin"), "Sleep every eligible villager now, whatever the load (tests, demos)",
            (sender, args) -> withEngine(sender, running -> {
                running.sleepEveryone();
                messages().send(sender, "sleep-started", "rate", running.settings().batchPerSecond());
            }), null);
        command.add("wakeall", "", permission("admin"), "Wake every sleeping villager and keep them awake (run before removing the plugin)",
            (sender, args) -> withEngine(sender, running -> {
                running.wakeEveryone(sender);
                messages().send(sender, "wakeall-started", "rate", running.settings().batchPerSecond());
            }), null);
        command.add("auto", "", permission("admin"), "Follow the server load again after sleep or wakeall",
            (sender, args) -> withEngine(sender, running -> {
                running.followLoad();
                messages().send(sender, "auto-started");
            }), null);
        command.add("check", "[radius]", permission("admin"), "Why each villager near you sleeps or not", this::check, null);
    }

    private void withEngine(CommandSender sender, java.util.function.Consumer<Engine> action) {
        Engine running = engine;
        if (running == null) {
            messages().send(sender, "plugin-off");
            return;
        }
        action.accept(running);
    }

    private void check(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "players-only");
            return;
        }
        Engine running = engine;
        if (running == null) {
            messages().send(sender, "plugin-off");
            return;
        }
        int radius = 8;
        if (args.length > 0) {
            try {
                radius = Math.max(1, Math.min(32, Integer.parseInt(args[0])));
            } catch (NumberFormatException notANumber) {
                messages().send(sender, "check-radius");
                return;
            }
        }
        // A player's command runs on the player's own thread; on Folia that region thread owns the villagers around them.
        checkAround(player, radius, running);
    }

    private void checkAround(Player player, int radius, Engine running) {
        Location here = player.getLocation();
        List<Villager> villagers = new ArrayList<>();
        for (Entity nearby : player.getNearbyEntities(radius, radius, radius)) {
            if (nearby instanceof Villager villager && (!platform().folia() || scheduler().isOwnedByCurrentRegion(villager))) {
                villagers.add(villager);
            }
        }
        villagers.sort(Comparator.comparingDouble(villager -> villager.getLocation().distanceSquared(here)));
        if (!running.load().global()) {
            Load.Reading reading = running.load().read(here);
            messages().send(player, "check-load", "reading", reading == null ? "no reading yet" : reading.display());
        }
        messages().send(player, "check-header", "count", villagers.size(), "radius", radius);
        long now = running.now();
        int shown = 0;
        for (Villager villager : villagers) {
            if (shown++ == 15) {
                messages().send(player, "check-more", "count", villagers.size() - 15);
                break;
            }
            Tracker.Tracked tracked = running.tracker().peek(villager.getUniqueId());
            long stillFor = tracked == null ? 0 : tracked.stillFor(now);
            long awakeUntil = tracked == null ? 0 : tracked.awakeUntil;
            Eligibility.Verdict verdict = running.eligibility().check(villager, stillFor, awakeUntil, now);
            String state = Marks.asleep(villager) ? messages().format("state-asleep")
                : villager.isAware() ? messages().format("state-awake") : messages().format("state-unaware");
            Location at = villager.getLocation();
            messages().send(player, "check-line", "state", state, "profession", professionName(villager),
                "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ(),
                "reason", messages().format(verdict.reason().messageKey(), "minutes", minutes(running.settings().stationaryMillis())));
        }
    }

    private static String professionName(Villager villager) {
        Object profession = villager.getProfession();
        return profession instanceof Keyed keyed ? keyed.getKey().getKey() : String.valueOf(profession);
    }

    private static String minutes(long millis) {
        double minutes = millis / 60_000.0;
        return minutes == Math.rint(minutes) ? String.valueOf((long) minutes) : String.format(Locale.ROOT, "%.1f", minutes);
    }

    @Override
    protected void status(List<String> lines) {
        Engine running = engine;
        if (running == null) {
            return;
        }
        Settings settings = running.settings();
        String hold = seconds(settings.holdMillis());
        Load.Reading reading = running.lastReading();
        // Which thresholds apply follows the reading's unit; before the first reading, and on Folia, both are shown.
        Load.Unit unit = running.load().global() && reading != null ? reading.unit() : null;
        if (running.load().global()) {
            lines.add(messages().format("status-load", "reading", reading == null ? "no reading yet" : reading.display(),
                "source", running.load().source()));
        } else {
            Engine.Pass pass = running.settled();
            lines.add(messages().format("status-load-regions", "source", running.load().source(),
                "behind", pass == null ? "?" : pass.behind.get(), "seen", pass == null ? "?" : pass.seen.get()));
        }
        if (unit != Load.Unit.TPS) {
            lines.add(messages().format("status-mspt", "sleep", number(settings.msptSleepAt()), "wake", number(settings.msptWakeAt()), "hold", hold));
        }
        if (unit != Load.Unit.MSPT) {
            lines.add(messages().format("status-tps", "sleep", number(settings.tpsSleepBelow()), "wake", number(settings.tpsWakeAt()), "hold", hold));
        }
        String mode = switch (running.mode()) {
            case AUTO -> running.load().global()
                ? messages().format(running.server().behind() ? "mode-auto-behind" : "mode-auto-keeping-up")
                : messages().format("mode-auto-regions");
            case SLEEP -> messages().format("mode-sleep");
            case AWAKE -> messages().format("mode-awake");
        };
        lines.add(messages().format("status-mode", "mode", mode));
        long held = running.load().global() ? running.server().heldFor(running.now()) : 0;
        if (held > 0) {
            lines.add(messages().format("status-held", "minutes", minutes(held + 59_999 - (held + 59_999) % 60_000)));
        }
        Engine.Pass pass = running.settled();
        if (pass == null) {
            lines.add(messages().format("status-first-scan"));
        } else {
            lines.add(messages().format("status-villagers", "seen", pass.seen.get(), "eligible", pass.eligible.get(),
                "asleep", pass.asleep.get(), "others", pass.others.get(), "every", seconds(settings.scanMillis()),
                "rate", settings.batchPerSecond()));
        }
        lines.add(messages().format("status-totals", "slept", totals.slept.sum(), "woken", totals.woken.sum(),
            "restocked", totals.restocked.sum(), "unload", totals.wokenOnUnload.sum(), "load", totals.wokenOnLoad.sum(),
            "kept", totals.keptOnLoad.sum()));
        lines.add(messages().format(settings.restockWhileAsleep()
            ? (restocker.serverRestock() ? "status-restock-server" : "status-restock-trades") : "status-restock-off"));
    }

    private static String number(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.2f", value).replaceAll("0+$", "");
    }

    private static String seconds(long millis) {
        return number(millis / 1000.0);
    }

    /** The server fell behind or caught up (Paper, Purpur, Spigot). */
    void alertLoad(boolean behind, String reading) {
        alerts().send(behind ? "behind" : "recovered", messages().format(behind ? "alert-behind" : "alert-recovered", "reading", reading));
    }

    /** Folia: the first villagers stand in regions that fell behind, or no region is behind any more. */
    void alertRegions(boolean behind, int villagers) {
        alerts().send(behind ? "regions-behind" : "regions-recovered",
            messages().format(behind ? "alert-regions-behind" : "alert-regions-recovered", "count", villagers));
    }

    void tellAllAwake(CommandSender sender) {
        if (!(sender instanceof Player player) || player.isOnline()) {
            messages().send(sender, "wakeall-done");
        }
    }

    /** Logs what went wrong at most once a minute, so one broken villager cannot flood the console. */
    void problem(String what, RuntimeException problem) {
        long now = System.currentTimeMillis();
        if (now - lastProblemLogged >= PROBLEM_LOG_GAP_MILLIS) {
            lastProblemLogged = now;
            getLogger().log(Level.WARNING, "Could not finish " + what + ": " + problem, problem);
        }
    }

    /** For tests: the running engine, or null while the plugin is off. */
    Engine engine() {
        return engine;
    }
}

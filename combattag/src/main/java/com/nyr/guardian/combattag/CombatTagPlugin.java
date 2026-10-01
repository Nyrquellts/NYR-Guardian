package com.nyr.guardian.combattag;

import com.nyr.guardian.common.GuardianPlugin;
import com.nyr.guardian.common.NyrCommand;
import com.tcoded.folialib.wrapper.task.WrappedTask;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * NYR CombatTag Pro. Players hit in PvP are tagged; blocked commands are refused while tagged; a tagged player who logs out
 * leaves a killable dummy that stands in for them.
 */
public class CombatTagPlugin extends GuardianPlugin {

    private final Tags tags = new Tags();
    private final Stats stats = new Stats();
    private volatile Settings settings;
    private MaxHealth maxHealth;
    private DummyBody body;
    private Dummies dummies;
    private KillRecordStore store;
    private WrappedTask heartbeat;
    private volatile LongSupplier clock = System::currentTimeMillis;
    private volatile BooleanSupplier stoppingProbe;
    private volatile boolean disabling;
    private Method isStopping;

    @Override
    public String pluginId() {
        return "combattag";
    }

    @Override
    public String displayName() {
        return "NYR CombatTag Pro";
    }

    @Override
    protected String commandName() {
        return "combattag";
    }

    @Override
    protected void startPlugin() {
        disabling = false;
        settings = Settings.read(settings(), getLogger());
        if (body == null) {
            maxHealth = new MaxHealth();
            body = new DummyBody(getLogger(), NamespacedKey.fromString("nyrcombattagpro:dummy"), maxHealth);
            dummies = new Dummies(this);
            isStopping = serverMethod("isStopping");
            if (!maxHealth.known()) {
                getLogger().warning("This server has no max-health attribute under a known key; dummies get the entity's own max health.");
            }
        }
        if (store == null) {
            store = new FileKillRecords(getDataFolder().toPath().resolve("data").resolve("killed"), getLogger());
        } else if (store instanceof FileKillRecords files) {
            files.index();
        }
        listen(new TagListener(this));
        listen(new DummyListener(this));
        heartbeat = scheduler().runTimer(this::heartbeat, 20L, 20L);
        World first = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
        getLogger().info("Combat lasts " + settings.combatSeconds() + " s; " + (settings.dummyEnabled()
            ? "a combat logger leaves a " + body.describeKind(settings, first) + " for " + settings.dummySeconds() + " s"
            : "no dummies (dummy.enabled is false)") + "; " + store.pending() + " kill record(s) wait for their owners.");
    }

    @Override
    protected void stopPlugin() {
        if (heartbeat != null) {
            scheduler().cancelTask(heartbeat);
            heartbeat = null;
        }
        // A reload keeps tags and standing dummies; disabling the plugin (or stopping the server) ends every dummy, and its
        // owner keeps everything, as after a restart.
        if (!isEnabled()) {
            disabling = true;
            dummies.endAll();
            tags.clear();
        }
    }

    /** Once a second: tags that ran out end with a message, everyone still in combat sees their countdown, dummies time out. */
    private void heartbeat() {
        long now = now();
        for (UUID id : tags.expire(now)) {
            Player player = getServer().getPlayer(id);
            if (player != null) {
                atEntity(player, () -> messages().send(player, "no-longer-in-combat"));
            }
        }
        tags.forEachLive(now, (id, left) -> {
            Player player = getServer().getPlayer(id);
            if (player != null) {
                int seconds = Tags.seconds(left);
                atEntity(player, () -> actionBar(player, seconds));
            }
        });
        dummies.tick(now);
    }

    // --- tagging ---

    /** A hit between two players: both are tagged, unless either is exempt. */
    void tagPair(Player victim, Player attacker) {
        if (victim.getUniqueId().equals(attacker.getUniqueId()) || !taggable(victim) || !taggable(attacker)) {
            return;
        }
        tag(victim, true);
        tag(attacker, true);
    }

    /** A mob's hit on a player (tag-on.mobs). */
    void tagAlone(Player victim) {
        if (taggable(victim)) {
            tag(victim, true);
        }
    }

    /** Creative and spectator players, bypassing players and worlds the plugin is off in are never tagged. */
    boolean taggable(Player player) {
        GameMode mode = player.getGameMode();
        return mode != GameMode.CREATIVE && mode != GameMode.SPECTATOR && !player.hasPermission(permission("bypass"))
            && worlds().allows(player.getWorld().getName());
    }

    /** Tags (or re-tags) the player; the start of a combat gets a chat line, every hit refreshes the action bar. */
    void tag(Player player, boolean announce) {
        Settings current = settings;
        boolean fresh = tags.tag(player.getUniqueId(), now(), current.combatMillis());
        if (fresh) {
            stats.tags.increment();
        }
        int seconds = current.combatSeconds();
        atEntity(player, () -> {
            if (fresh && announce) {
                messages().send(player, "tagged", "seconds", seconds);
            }
            actionBar(player, seconds);
        });
    }

    /** The owner came back before their dummy died: in combat again, told why. */
    void tagAgain(Player player) {
        tag(player, false);
        messages().send(player, "dummy-returned", "seconds", settings.combatSeconds());
    }

    @SuppressWarnings("deprecation")
    private void actionBar(Player player, int seconds) {
        String text = messages().format("action-bar", "seconds", seconds);
        if (!text.isEmpty() && player.isOnline()) {
            player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(text));
        }
    }

    // --- threads ---

    /** Runs the task on the thread that owns the entity: now if this is it, otherwise scheduled there (Folia). */
    void atEntity(Entity entity, Runnable task) {
        if (scheduler().isOwnedByCurrentRegion(entity)) {
            task.run();
        } else {
            scheduler().runAtEntity(entity, wrapped -> task.run());
        }
    }

    /** A chat line for every online player (each on their own thread) and the console. */
    void broadcast(String message) {
        if (message.isEmpty()) {
            return;
        }
        getServer().getConsoleSender().sendMessage(message);
        for (Player player : getServer().getOnlinePlayers()) {
            atEntity(player, () -> player.sendMessage(message));
        }
    }

    /** Paper's Server#isStopping where it exists, otherwise whether this plugin is being disabled. */
    boolean serverStopping() {
        BooleanSupplier probe = stoppingProbe;
        if (probe != null) {
            return probe.getAsBoolean();
        }
        if (disabling) {
            return true;
        }
        if (isStopping != null) {
            try {
                return Boolean.TRUE.equals(isStopping.invoke(getServer()));
            } catch (ReflectiveOperationException | RuntimeException notAnswered) {
                return false;
            }
        }
        return false;
    }

    private static Method serverMethod(String name) {
        try {
            return org.bukkit.Server.class.getMethod(name);
        } catch (NoSuchMethodException | SecurityException absent) {
            return null;
        }
    }

    // --- commands ---

    @Override
    protected void commands(NyrCommand command) {
        command.add("check", "", permission("check"), "Whether you are in combat and for how long", this::check, null)
            .add("tag", "<player>", permission("admin"), "Put a player in combat", this::tagCommand, this::onlineNames)
            .add("untag", "<player>", permission("admin"), "Take a player out of combat", this::untagCommand, this::onlineNames)
            .add("dummies", "", permission("admin"), "Standing combat-log dummies", this::listDummies, null)
            .byDefault("check");
    }

    private void check(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "players-only");
            return;
        }
        long left = tags.remainingMillis(player.getUniqueId(), now());
        if (left > 0) {
            messages().send(player, "check-tagged", "seconds", Tags.seconds(left));
        } else {
            messages().send(player, "check-not-tagged");
        }
    }

    private void tagCommand(CommandSender sender, String[] args) {
        Player target = target(sender, args, "tag");
        if (target != null) {
            tag(target, true);
            messages().send(sender, "tag-done", "player", target.getName(), "seconds", settings.combatSeconds());
        }
    }

    private void untagCommand(CommandSender sender, String[] args) {
        Player target = target(sender, args, "untag");
        if (target != null) {
            boolean was = tags.untag(target.getUniqueId(), now());
            messages().send(sender, was ? "untag-done" : "untag-none", "player", target.getName());
        }
    }

    private Player target(CommandSender sender, String[] args, String subcommand) {
        if (args.length < 1) {
            messages().send(sender, "usage", "usage", "/" + commandName() + " " + subcommand + " <player>");
            return null;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null) {
            messages().send(sender, "player-not-found", "player", args[0]);
        }
        return target;
    }

    private List<String> onlineNames(CommandSender sender, String[] args) {
        List<String> names = new ArrayList<>();
        if (args.length <= 1) {
            for (Player player : getServer().getOnlinePlayers()) {
                names.add(player.getName());
            }
        }
        return names;
    }

    private void listDummies(CommandSender sender, String[] args) {
        long now = now();
        var standing = dummies.standing();
        messages().send(sender, "dummies-header", "count", standing.size());
        if (standing.isEmpty()) {
            messages().send(sender, "dummies-none");
            return;
        }
        for (DummyState state : standing) {
            Location at = state.snapshot.location();
            messages().send(sender, "dummies-line", "player", state.ownerName, "kind", state.bodyKind,
                "world", at.getWorld() == null ? "?" : at.getWorld().getName(), "x", at.getBlockX(), "y", at.getBlockY(), "z", at.getBlockZ(),
                "health", String.format(Locale.ROOT, "%.1f", state.health), "seconds", Tags.seconds(state.until - now));
        }
    }

    @Override
    protected void status(List<String> lines) {
        Settings current = settings;
        if (current == null) {
            return;
        }
        long now = now();
        World first = getServer().getWorlds().isEmpty() ? null : getServer().getWorlds().get(0);
        lines.add("&7Combat: &f" + current.combatSeconds() + "s &8| &7in combat now: &f" + tags.count(now) + " &8| &7commands: &f"
            + current.commands().mode().name().toLowerCase(Locale.ROOT) + " &7of &f" + current.commands().size());
        lines.add("&7Dummies: " + (current.dummyEnabled() ? "&f" + body.describeKind(current, first) + " &7for &f" + current.dummySeconds() + "s"
            : "&coff") + " &8| &7standing now: &f" + dummies.standing().size() + " &8| &7kill records waiting: &f" + store.pending());
        lines.add("&7Since start: &ftags " + stats.tags.sum() + " &8| &fcommands refused " + stats.commandsRefused.sum() + " &8| &fdummies "
            + stats.dummies.sum() + " &8| &fkilled " + stats.killed.sum() + " &8| &freturned " + stats.returned.sum() + " &8| &fexpired "
            + stats.expired.sum() + " &8| &fkills applied on join " + stats.applied.sum());
        if (stats.spawnFailed.sum() > 0 || stats.recordFailed.sum() > 0) {
            lines.add("&cDummies that could not spawn: " + stats.spawnFailed.sum() + " &8| &crecords that could not be written: "
                + stats.recordFailed.sum() + " &7(see the console)");
        }
    }

    // --- shared state ---

    long now() {
        return clock.getAsLong();
    }

    Settings currentSettings() {
        return settings;
    }

    Tags tags() {
        return tags;
    }

    Stats stats() {
        return stats;
    }

    Dummies dummies() {
        return dummies;
    }

    DummyBody body() {
        return body;
    }

    MaxHealth maxHealth() {
        return maxHealth;
    }

    KillRecordStore store() {
        return store;
    }

    // --- for tests ---

    void clock(LongSupplier clock) {
        this.clock = clock;
    }

    void recordStore(KillRecordStore store) {
        this.store = store;
    }

    void stoppingProbe(BooleanSupplier probe) {
        this.stoppingProbe = probe;
    }
}

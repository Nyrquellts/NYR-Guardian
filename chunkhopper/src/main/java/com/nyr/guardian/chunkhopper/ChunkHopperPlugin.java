package com.nyr.guardian.chunkhopper;

import com.nyr.guardian.common.GuardianPlugin;
import com.nyr.guardian.common.NyrCommand;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

/**
 * NYR ChunkHopper. One Chunk Hopper per chunk collects every drop in that chunk the moment it spawns, filters it, and with
 * a Vault economy sells it into its owner's balance.
 */
public class ChunkHopperPlugin extends GuardianPlugin {

    private static final int MAX_GIVE = 36 * 64;

    private final Stats stats = new Stats();
    private final Keys keys = new Keys();
    private HopperItems items;
    private Registry registry;
    private SaleJournal journal;
    private Hoppers hoppers;
    private Seller seller;
    private Collector collector;
    private Guard guard;
    private Menus menus;
    private boolean recipeAdded;

    @Override
    public String pluginId() {
        return "chunkhopper";
    }

    @Override
    public String displayName() {
        return "NYR ChunkHopper";
    }

    @Override
    protected String commandName() {
        return "chunkhopper";
    }

    @Override
    protected Set<String> freeFormSections() {
        return Set.of("prices", "recipe.ingredients");
    }

    @Override
    protected void startPlugin() {
        YamlConfiguration settings = settings();
        items = new HopperItems(keys, settings.getString("item.name", "&6Chunk Hopper"), settings.getStringList("item.lore"),
            settings.getBoolean("item.glint", true));
        registry = new Registry(new File(getDataFolder(), "hoppers.yml"), getLogger());
        registry.load();
        journal = new SaleJournal(new File(getDataFolder(), "sales.journal"), getLogger());
        try {
            journal.open();
        } catch (IOException unreadable) {
            getLogger().severe("sales.journal cannot be opened (" + unreadable.getMessage() + "): Chunk Hoppers collect but do not sell until it can.");
        }
        hoppers = new Hoppers(getServer(), scheduler(), getLogger(), new HopperStore(keys), registry, stats, journal, name -> worlds().allows(name));
        hoppers.whenRolledBack(rollback -> alerts().send("rollback:" + rollback.record().id, messages().format("alert-rolled-back",
            "world", rollback.world().getName(), "x", rollback.record().x, "y", rollback.record().y, "z", rollback.record().z,
            "sales", rollback.sales(), "items", rollback.items(), "amount", seller == null ? rollback.paid() : seller.payout().format(rollback.paid()),
            "taken", rollback.taken())));

        Prices.Source source = Prices.Source.parse(settings.getString("auto-sell.price-source", "auto"));
        Prices prices = Prices.from(settings.getConfigurationSection("prices"), source,
            source == Prices.Source.AUTO ? Prices.EssentialsWorth.find(getServer().getPluginManager().getPlugin("Essentials"), getLogger()) : null);
        if (!prices.unknown().isEmpty()) {
            getLogger().warning("config.yml prices: this server has no item called " + String.join(", ", prices.unknown()) + "; those prices are skipped.");
        }
        Payout payout = Payout.find(getServer(), getClass().getClassLoader());
        seller = new Seller(getServer(), scheduler(), getLogger(), hoppers, journal, prices, payout, stats, messages(), alerts(),
            Math.max(1, settings.getInt("auto-sell.interval-seconds", 10)), settings.getBoolean("auto-sell.notify-owner", true));
        collector = new Collector(hoppers, seller, stats, scheduler(), settings.getBoolean("collect.player-mined", false));
        guard = new Guard(hoppers, items, messages(), permission("use"), permission("admin"), permission("limit."),
            Math.max(0, settings.getInt("limits.per-player", 0)), settings.getBoolean("protect-from-others", true),
            settings.getBoolean("explosion-proof", true), settings.getBoolean("auto-sell.default-on", true));
        menus = new Menus(getServer(), scheduler(), hoppers, items, seller, messages(), settings.getConfigurationSection("messages"),
            alerts(), stats, permission("use"), permission("admin"));
        hoppers.whenGone(menus::closeAll);
        Collector purging = collector;
        Guard guarding = guard;
        seller.everyInterval(() -> {
            purging.purge();
            guarding.purge();
        });

        listen(collector);
        listen(guard);
        listen(menus);
        seller.start();
        addRecipe();
        hoppers.resume(!platform().folia());
    }

    @Override
    protected void stopPlugin() {
        if (seller != null) {
            seller.stop();
        }
        if (menus != null) {
            menus.closeEverything();
        }
        removeRecipe();
        if (hoppers != null) {
            // While the server shuts down the region threads no longer run tasks: write on this thread or not at all.
            boolean shuttingDown = !isEnabled();
            for (HopperRecord record : hoppers.all()) {
                if (record.statsDirty()) {
                    writeCounters(hoppers, record, shuttingDown);
                }
            }
            hoppers.clear();
        }
        if (registry != null) {
            registry.close();
        }
        if (journal != null) {
            journal.close();
        }
        items = null;
        journal = null;
        hoppers = null;
        seller = null;
        collector = null;
        guard = null;
        menus = null;
        registry = null;
    }

    private void writeCounters(Hoppers from, HopperRecord record, boolean now) {
        World world = from.world(record);
        if (world == null) {
            return;
        }
        Location at = record.center(world);
        Runnable write = () -> {
            if (world.isChunkLoaded(record.chunk.x(), record.chunk.z())) {
                record.statsWritten();
                from.store.write(world.getBlockAt(record.x, record.y, record.z), record, false);
            }
        };
        if (now || scheduler().isOwnedByCurrentRegion(at)) {
            try {
                write.run();
            } catch (RuntimeException refused) {
                getLogger().log(Level.FINE, "Could not write " + record + " while stopping", refused);
            }
        } else {
            scheduler().runAtLocation(at, ignored -> write.run());
        }
    }

    // ------------------------------------------------------------------ recipe

    private void addRecipe() {
        if (!settings().getBoolean("recipe.enabled", true)) {
            return;
        }
        List<String> shape = settings().getStringList("recipe.shape");
        ConfigurationSection ingredients = settings().getConfigurationSection("recipe.ingredients");
        try {
            ShapedRecipe recipe = new ShapedRecipe(keys.recipe, items.create(1));
            recipe.shape(shape.toArray(new String[0]));
            Set<Character> letters = new LinkedHashSet<>();
            for (String row : shape) {
                for (char letter : row.toCharArray()) {
                    if (letter != ' ') {
                        letters.add(letter);
                    }
                }
            }
            for (char letter : letters) {
                String id = ingredients == null ? null : ingredients.getString(String.valueOf(letter));
                Material material = id == null ? null : Material.matchMaterial(id);
                if (material == null || !material.isItem() || material.isAir()) {
                    throw new IllegalArgumentException("recipe.ingredients has no item for '" + letter + "'" + (id == null ? "" : " (" + id + ")"));
                }
                recipe.setIngredient(letter, material);
            }
            getServer().removeRecipe(keys.recipe);
            recipeAdded = getServer().addRecipe(recipe);
        } catch (RuntimeException broken) {
            getLogger().warning("The Chunk Hopper recipe in config.yml cannot be used (" + broken.getMessage() + "); no recipe was added.");
        }
    }

    private void removeRecipe() {
        if (recipeAdded) {
            recipeAdded = false;
            try {
                getServer().removeRecipe(keys.recipe);
            } catch (RuntimeException ignored) {
                // the recipe goes with the server
            }
        }
    }

    // ------------------------------------------------------------------ commands

    @Override
    protected void commands(NyrCommand command) {
        command.add("give", "<player> [amount]", permission("admin"), "Give Chunk Hoppers", this::give, this::completePlayers)
            .add("list", "[player]", permission("use"), "Where your (or a player's) Chunk Hoppers are", this::list, this::completeList)
            .add("info", "", permission("use"), "The Chunk Hopper you are looking at", this::info, null);
    }

    private boolean on(CommandSender sender) {
        if (!running() || hoppers == null) {
            messages().send(sender, "plugin-off");
            return false;
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!on(sender)) {
            return;
        }
        if (args.length < 1 || args.length > 2) {
            messages().send(sender, "give-usage");
            return;
        }
        Player target = getServer().getPlayerExact(args[0]);
        if (target == null) {
            messages().send(sender, "player-not-found", "player", args[0]);
            return;
        }
        int amount = 1;
        if (args.length == 2) {
            try {
                amount = Integer.parseInt(args[1]);
            } catch (NumberFormatException notANumber) {
                amount = 0;
            }
            if (amount < 1 || amount > MAX_GIVE) {
                messages().send(sender, "give-usage");
                return;
            }
        }
        List<ItemStack> stacks = new ArrayList<>();
        for (int left = amount; left > 0; left -= 64) {
            stacks.add(items.create(Math.min(64, left)));
        }
        int given = amount;
        Runnable hand = () -> {
            Map<Integer, ItemStack> rest = target.getInventory().addItem(stacks.toArray(new ItemStack[0]));
            if (!rest.isEmpty()) {
                Location feet = target.getLocation();
                Collector.ownDrops(() -> rest.values().forEach(stack -> target.getWorld().dropItemNaturally(feet, stack)));
            }
            messages().send(target, "received", "amount", given);
        };
        if (scheduler().isOwnedByCurrentRegion(target)) {
            hand.run();
        } else {
            scheduler().runAtEntity(target, ignored -> hand.run());
        }
        messages().send(sender, "given", "amount", amount, "player", target.getName());
    }

    private List<String> completePlayers(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (Player player : getServer().getOnlinePlayers()) {
                out.add(player.getName());
            }
        } else if (args.length == 2) {
            out.addAll(List.of("1", "16", "64"));
        }
        return out;
    }

    private List<String> completeList(CommandSender sender, String[] args) {
        return args.length == 1 && sender.hasPermission(permission("admin")) ? completePlayers(sender, args) : List.of();
    }

    private void list(CommandSender sender, String[] args) {
        if (!on(sender)) {
            return;
        }
        UUID owner;
        String name;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                messages().send(sender, "players-only");
                return;
            }
            owner = player.getUniqueId();
            name = player.getName();
        } else {
            boolean self = sender instanceof Player player && player.getName().equalsIgnoreCase(args[0]);
            if (!self && !sender.hasPermission(permission("admin"))) {
                messages().send(sender, "no-permission");
                return;
            }
            Player online = getServer().getPlayerExact(args[0]);
            owner = online != null ? online.getUniqueId() : registry.ownerNamed(args[0]);
            name = online != null ? online.getName() : args[0];
            if (owner == null) {
                messages().send(sender, "player-not-found", "player", args[0]);
                return;
            }
        }
        List<Registry.Entry> owned = registry.owned(owner);
        messages().send(sender, "list-header", "player", name, "count", owned.size());
        if (owned.isEmpty()) {
            messages().send(sender, "list-empty");
        }
        for (Registry.Entry entry : owned) {
            messages().send(sender, "list-line", "world", entry.worldName(), "x", entry.x(), "y", entry.y(), "z", entry.z());
        }
    }

    private void info(CommandSender sender, String[] args) {
        if (!on(sender)) {
            return;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "players-only");
            return;
        }
        Block target = player.getTargetBlockExact(5);
        HopperRecord record = target == null || target.getType() != Material.HOPPER ? null : hoppers.at(target);
        Hopper hopper = record == null ? null : hoppers.live(record, target.getWorld());
        if (hopper == null) {
            messages().send(player, "info-none");
            return;
        }
        if (!record.ownedBy(player.getUniqueId()) && !player.hasPermission(permission("admin"))) {
            messages().send(player, "not-yours", "owner", record.ownerName());
            return;
        }
        messages().send(player, "info-header", "world", target.getWorld().getName(), "x", record.x, "y", record.y, "z", record.z);
        messages().send(player, "info-owner", "owner", record.ownerName());
        messages().send(player, "info-filter", "filter", record.filter().describe(messages().format("filter-everything")));
        if (seller.canSell()) {
            messages().send(player, "info-sell", "state", messages().format(record.autoSell() ? "state-yes" : "state-no"));
        }
        messages().send(player, "info-counters", "collected", record.collected(), "earned", seller.payout().format(record.earned()));
        messages().send(player, "info-holds", "items", holds(hopper.getInventory()));
    }

    /** "12 cactus, 3 bone" for the hopper's contents, material by material. */
    private String holds(Inventory inventory) {
        Map<Material, Integer> counts = new LinkedHashMap<>();
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && !stack.getType().isAir()) {
                counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
            }
        }
        if (counts.isEmpty()) {
            return messages().format("holds-nothing");
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : counts.entrySet()) {
            parts.add(entry.getValue() + " " + entry.getKey().getKey().getKey().toLowerCase(Locale.ROOT));
        }
        return String.join(", ", parts);
    }

    // ------------------------------------------------------------------ status

    @Override
    protected void status(List<String> lines) {
        if (hoppers == null) {
            return;
        }
        lines.add("&7Chunk Hoppers loaded: &f" + hoppers.loadedCount() + " &8| &7listed in hoppers.yml: &f" + registry.size()
            + " &8| &7placed since start: &f" + stats.placed.sum() + " &8| &7removed: &f" + stats.removed.sum());
        lines.add("&7Collected since start: &f" + stats.collected.sum() + " &7items &8| &7sold: &f" + stats.sold.sum() + " &7items for &f"
            + seller.payout().format(stats.earned.sum()) + " &8| &7sales refused by the economy: &f" + stats.refusedSales.sum());
        lines.add("&7Left on the ground: &fthrown or dropped by players " + stats.droppedByPlayers.sum() + " &8| &fdeath drops "
            + stats.deathDrops.sum() + " &8| &ffishing catches " + stats.fishingCatches.sum() + " &8| &fplayer-mined "
            + (stats.minedByDropEvent.sum() + stats.minedByBreakCell.sum()) + " (by BlockDropItemEvent " + stats.minedByDropEvent.sum()
            + ", by break position " + stats.minedByBreakCell.sum() + ") &8| &fnot in the filter " + stats.notInFilter.sum()
            + " &8| &fhopper full " + stats.full.sum());
        lines.add("&7BlockDropItemEvent items: &f" + stats.dropEventItems.sum() + " &8| &7already spawned when it fired: &f"
            + stats.dropEventItemsAlreadySpawned.sum() + " &8| &7Chunk Hopper breaks: &f" + stats.hopperBreaks.sum() + " &7listing &f"
            + stats.hopperBreakItems.sum() + " &7items &8| &7explosions stopped: &f" + stats.explosionsBlocked.sum()
            + " &8| &7filter icons removed from players: &f" + stats.ghostsRemoved.sum());
        lines.add("&7Sale journal: &f" + journal.size() + " &7sale(s) not yet known to be saved" + (journal.broken() ? " &c(cannot be written)" : "")
            + " &8| &7awaiting payment: &f" + seller.unpaid() + " &8| &7crash rollbacks undone: &f" + stats.rolledBackSales.sum()
            + " &7sale(s), &f" + stats.rolledBackItems.sum() + " &7item(s) taken out again");
        lines.add("&7Economy: &f" + seller.payout().name() + (seller.canSell() ? "" : " &c(not ready)") + " &8| &7prices: &f"
            + seller.prices().describe() + " &8| &7auto-sell every &f" + seller.intervalSeconds() + " s");
    }
}

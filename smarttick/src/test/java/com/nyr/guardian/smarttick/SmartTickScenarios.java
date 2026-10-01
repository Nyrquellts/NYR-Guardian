package com.nyr.guardian.smarttick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.opentest4j.TestAbortedException;

/**
 * What SmartTick must do, through the Bukkit API and its commands only, so the same checks run on the compiled classes and on
 * the built jar. The load is forced through config.yml: thresholds every reading passes make the server "behind", thresholds
 * no reading reaches make it "caught up", whatever this machine's real tick times are.
 */
public final class SmartTickScenarios {

    private static final NamespacedKey ASLEEP = NamespacedKey.fromString("nyrsmarttick:asleep");
    private static final Object[] BEHIND = {"mspt.sleep-at", 0.0, "mspt.wake-at", 0.0, "tps.sleep-below", 1000.0, "tps.wake-at", 1000.0};
    private static final Object[] CAUGHT_UP = {"mspt.sleep-at", 1e9, "mspt.wake-at", 1e9, "tps.sleep-below", 0.0, "tps.wake-at", 0.0};

    private final ServerMock server;
    private final Plugin plugin;
    private final TestWorld world;
    private final PlayerMock staff;
    private FakeVillager cellA;
    private FakeVillager cellB;
    private FakeVillager cellC;
    private FakeVillager withBed;
    private FakeVillager nitwit;
    private FakeVillager baby;
    private FakeVillager free;
    private FakeVillager inCart;
    private FakeVillager otherPlugins;

    public SmartTickScenarios(ServerMock server, Plugin plugin) {
        this.server = server;
        this.plugin = plugin;
        this.world = new TestWorld("hall");
        server.addWorld(world);
        this.staff = server.addPlayer("Staff");
        staff.setOp(true);
        staff.teleport(new Location(world, 7.5, 64, 7.5));
    }

    public void all() throws Exception {
        try {
            build();
            statusNamesTheReadingModeAndThresholds();
            onlyEligibleVillagersSleepWhenTheServerIsBehind();
            tradingWakesAVillagerAtOnce();
            sleepingVillagersRestockLikeTheGame();
            unloadingAChunkWakesItsSleepers();
            aChunkLoadedWithASleeperKeepsItAsleepWhileBehind();
            wakeallKeepsEveryoneAwakeUntilAuto();
            sleepCommandSleepsEligibleVillagersWhateverTheLoad();
            villagersWakeWhenTheServerCatchesUp();
            aChunkLoadedWithASleeperWakesItWhenCaughtUp();
            batchesLimitHowManyChangeEachSecond();
            reloadingKeepsSleepersAndSwitchingOffWakesThem();
            disablingThePluginWakesEverybody();
        } catch (TestAbortedException unimplemented) {
            fail("MockBukkit could not run part of the scenario, so it proved nothing: " + unimplemented.getMessage(), unimplemented);
        }
    }

    // ---- building the hall ----

    private void block(int x, int y, int z, Material type) {
        world.getBlockAt(x, y, z).setType(type);
    }

    /** A 1x1 cell: glass on three sides at feet and head height, the job site in front with air above it, a glass roof. */
    private FakeVillager cell(int x, int z, Villager.Profession profession) {
        for (int y = 64; y <= 65; y++) {
            block(x - 1, y, z, Material.GLASS);
            block(x + 1, y, z, Material.GLASS);
            block(x, y, z - 1, Material.GLASS);
        }
        block(x, 64, z + 1, Material.COMPOSTER);
        block(x, 66, z, Material.GLASS);
        FakeVillager villager = FakeVillager.spawn(server, new Location(world, x + 0.5, 64, z + 0.5));
        villager.setProfession(profession);
        villager.setMemory(MemoryKey.JOB_SITE, new Location(world, x, 64, z + 1));
        return villager;
    }

    private void build() {
        cellA = cell(2, 2, Villager.Profession.FARMER);
        cellB = cell(5, 2, Villager.Profession.FARMER);
        cellC = cell(8, 2, Villager.Profession.FARMER);
        withBed = cell(11, 2, Villager.Profession.FARMER);
        withBed.setMemory(MemoryKey.HOME, new Location(world, 11, 64, 6));
        nitwit = cell(2, 8, Villager.Profession.NITWIT);
        nitwit.setMemory(MemoryKey.JOB_SITE, null);
        baby = cell(5, 8, Villager.Profession.FARMER);
        baby.setBaby();
        otherPlugins = cell(11, 8, Villager.Profession.FARMER);
        otherPlugins.setAware(false);
        block(8, 64, 13, Material.COMPOSTER);
        free = FakeVillager.spawn(server, new Location(world, 8.5, 64, 12.5));
        free.setProfession(Villager.Profession.FARMER);
        free.setMemory(MemoryKey.JOB_SITE, new Location(world, 8, 64, 13));
        block(12, 64, 11, Material.COMPOSTER);
        inCart = FakeVillager.spawn(server, new Location(world, 12.5, 64, 12.5));
        inCart.setProfession(Villager.Profession.FARMER);
        inCart.setMemory(MemoryKey.JOB_SITE, new Location(world, 12, 64, 11));
        RideableMinecart cart = world.spawn(new Location(world, 12.5, 64, 12.5), RideableMinecart.class);
        assertTrue(cart.addPassenger(inCart), "the villager sits in the minecart");
        assertTrue(inCart.isInsideVehicle());
    }

    // ---- helpers ----

    private static boolean marked(Villager villager) {
        return villager.getPersistentDataContainer().has(ASLEEP, PersistentDataType.LONG);
    }

    private static boolean asleep(Villager villager) {
        return !villager.isAware() && marked(villager);
    }

    private static boolean awake(Villager villager) {
        return villager.isAware() && !marked(villager);
    }

    private List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = player.nextMessage()) != null) {
            lines.add(ChatColor.stripColor(line));
        }
        return lines;
    }

    private List<String> run(String command) {
        drain(staff);
        assertTrue(server.dispatchCommand(staff, command), command);
        return drain(staff);
    }

    /** Writes settings into config.yml and reloads, as an owner does. Always with a fast scan and no hold, for quick tests. */
    private void configure(Object... pairs) throws Exception {
        File file = new File(plugin.getDataFolder(), "config.yml");
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        yaml.set("hold-seconds", 0.0);
        yaml.set("scan-seconds", 1.0);
        for (int i = 0; i < pairs.length; i += 2) {
            yaml.set((String) pairs[i], pairs[i + 1]);
        }
        yaml.save(file);
        List<String> reply = run("smarttick reload");
        assertTrue(reply.stream().anyMatch(line -> line.contains("Reloaded config.yml")), reply.toString());
    }

    private static Object[] with(Object[] base, Object... more) {
        Object[] all = new Object[base.length + more.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(more, 0, all, base.length, more.length);
        return all;
    }

    private void ticks(int count) {
        server.getScheduler().performTicks(count);
    }

    // ---- scenarios ----

    void statusNamesTheReadingModeAndThresholds() {
        ticks(5);
        List<String> status = run("smarttick");
        String all = String.join("\n", status);
        assertTrue(all.contains("NYR SmartTick") && all.contains("on"), all);
        assertTrue(all.contains("Load:"), all);
        assertTrue(all.contains("Sleep at 40 ms") || all.contains("Sleep below 19 TPS"), all);
        assertTrue(all.contains("Mode: following the load"), all);
        List<String> help = run("smarttick help");
        String commands = String.join("\n", help);
        for (String sub : List.of("status", "reload", "sleep", "wakeall", "auto", "check")) {
            assertTrue(commands.contains("/smarttick " + sub), commands);
        }
    }

    void onlyEligibleVillagersSleepWhenTheServerIsBehind() throws Exception {
        configure(with(BEHIND, "batch-per-second", 1000));
        ticks(45);
        for (FakeVillager sleeper : List.of(cellA, cellB, cellC, inCart)) {
            assertTrue(asleep(sleeper), "a walled-in or seated villager with a job site sleeps: " + sleeper.getLocation());
        }
        for (FakeVillager keeper : List.of(withBed, nitwit, baby, free)) {
            assertTrue(awake(keeper), "a villager with a bed, a nitwit, a baby or a free villager stays awake: " + keeper.getLocation());
        }
        assertFalse(otherPlugins.isAware(), "another plugin's unaware villager stays as it was");
        assertFalse(marked(otherPlugins), "and SmartTick never marks it");
        String status = String.join("\n", run("smarttick status"));
        assertTrue(status.contains("server is behind"), status);
        assertTrue(status.contains("4 asleep") && status.contains("1 put to sleep by other plugins"), status);
        List<String> check = run("smarttick check 16");
        assertTrue(check.stream().anyMatch(line -> line.contains("asleep") && line.contains("walled in")), check.toString());
        assertTrue(check.stream().anyMatch(line -> line.contains("has a bed")), check.toString());
        assertTrue(check.stream().anyMatch(line -> line.contains("can walk away")), check.toString());
    }

    void tradingWakesAVillagerAtOnce() {
        assertTrue(asleep(cellA));
        server.getPluginManager().callEvent(Trades.open(staff, cellA));
        assertTrue(awake(cellA), "opening a sleeping villager's trades wakes it at once");
        cellA.setTrader(staff);
        ticks(45);
        assertTrue(awake(cellA), "it stays awake while the player trades");
        cellA.setTrader(null);
        server.getPluginManager().callEvent(Trades.close(staff, cellA));
        ticks(45);
        assertTrue(awake(cellA), "and a little while after, so it can level up as the game does");

        // Paper 1.20.6 hands the trade window a merchant wrapper instead of the villager.
        FakeVillager wrapped = cell(14, 8, Villager.Profession.FLETCHER);
        ticks(45);
        assertTrue(asleep(wrapped));
        wrapped.setTrader(staff);
        server.getPluginManager().callEvent(Trades.openThroughWrapper(staff));
        assertTrue(awake(wrapped), "a sleeping villager whose trades open through a wrapper is found by its trading partner and woken");
        wrapped.setTrader(null);
    }

    void sleepingVillagersRestockLikeTheGame() {
        assertTrue(asleep(cellB));
        MerchantRecipe wheat = new MerchantRecipe(new ItemStack(Material.EMERALD), 5, 16, true, 2, 0.05f, 0, 0);
        wheat.addIngredient(new ItemStack(Material.WHEAT, 20));
        cellB.setRecipes(List.of(wheat));
        world.setFullTime(3000);
        ticks(25);
        assertEquals(0, cellB.getRecipe(0).getUses(), "a sleeping villager next to its job site restocks in work hours");
        assertEquals(-6, cellB.getRecipe(0).getDemand(), "with the game's demand update: 0 + 5 - (16 - 5)");
        assertEquals(1, cellB.getRestocksToday(), "counted as one of the day's restocks");
        cellB.getRecipe(0).setUses(3);
        world.setFullTime(3000 + 2000);
        ticks(25);
        assertEquals(3, cellB.getRecipe(0).getUses(), "not again within 2400 ticks of the first");
        world.setFullTime(3000 + 2401);
        ticks(25);
        assertEquals(0, cellB.getRecipe(0).getUses(), "the second restock of the day");
        cellB.getRecipe(0).setUses(4);
        world.setFullTime(8900);
        ticks(25);
        assertEquals(4, cellB.getRecipe(0).getUses(), "never a third in one day");
        world.setFullTime(24_000 + 10_000);
        ticks(25);
        assertEquals(4, cellB.getRecipe(0).getUses(), "never outside work hours");
        world.setFullTime(24_000 + 2500);
        ticks(25);
        assertEquals(0, cellB.getRecipe(0).getUses(), "the next day restocks again");
        assertTrue(asleep(cellB), "and it slept through all of it");
    }

    void unloadingAChunkWakesItsSleepers() {
        assertTrue(asleep(cellC));
        server.getPluginManager().callEvent(new EntitiesUnloadEvent(world.getChunkAt(0, 0), List.<Entity>of(cellC)));
        assertTrue(awake(cellC), "a sleeper is woken before its chunk is saved, so it is never saved asleep out of reach");
    }

    /** A villager saved asleep, as a hard stop or Folia's shutdown leaves it: aware off and SmartTick's mark on. */
    private void savedAsleep(Villager villager) {
        villager.getPersistentDataContainer().set(ASLEEP, PersistentDataType.LONG, System.currentTimeMillis());
        villager.setAware(false);
    }

    void aChunkLoadedWithASleeperKeepsItAsleepWhileBehind() {
        savedAsleep(cellC);
        server.getPluginManager().callEvent(new EntitiesLoadEvent(world.getChunkAt(0, 0), List.<Entity>of(cellC)));
        assertTrue(asleep(cellC), "an eligible villager loaded asleep while the server is behind stays asleep");
        savedAsleep(withBed);
        server.getPluginManager().callEvent(new EntitiesLoadEvent(world.getChunkAt(0, 0), List.<Entity>of(withBed)));
        assertTrue(awake(withBed), "one that may not sleep wakes as it loads");
    }

    void wakeallKeepsEveryoneAwakeUntilAuto() {
        ticks(45);
        assertTrue(asleep(cellB) && asleep(cellC) && asleep(inCart));
        List<String> reply = run("smarttick wakeall");
        assertTrue(reply.stream().anyMatch(line -> line.contains("Waking every sleeping villager")), reply.toString());
        ticks(45);
        for (FakeVillager villager : List.of(cellA, cellB, cellC, inCart, withBed, nitwit, baby, free)) {
            assertTrue(awake(villager), "wakeall wakes everyone, though the server is still behind: " + villager.getLocation());
        }
        assertFalse(otherPlugins.isAware(), "but never another plugin's villager");
        assertTrue(drain(staff).stream().anyMatch(line -> line.contains("Every loaded villager is awake")), "and says when it is done");
        String status = String.join("\n", run("smarttick status"));
        assertTrue(status.contains("keeping every villager awake"), status);
        run("smarttick auto");
        ticks(45);
        assertTrue(asleep(cellB) && asleep(cellC) && asleep(inCart), "auto follows the load again");
    }

    void sleepCommandSleepsEligibleVillagersWhateverTheLoad() throws Exception {
        configure(CAUGHT_UP);
        ticks(45);
        assertTrue(awake(cellB) && awake(cellC) && awake(inCart), "caught up, everyone wakes");
        List<String> reply = run("smarttick sleep");
        assertTrue(reply.stream().anyMatch(line -> line.contains("Every eligible villager goes to sleep now")), reply.toString());
        ticks(45);
        // cellA traded moments ago and stays awake for a little while yet.
        assertTrue(asleep(cellB) && asleep(cellC) && asleep(inCart), "sleep puts every eligible villager to sleep");
        assertTrue(awake(withBed) && awake(nitwit) && awake(baby) && awake(free), "and only them");
        run("smarttick auto");
        ticks(45);
    }

    void villagersWakeWhenTheServerCatchesUp() {
        for (FakeVillager villager : List.of(cellA, cellB, cellC, inCart)) {
            assertTrue(awake(villager), "back on auto while caught up, sleepers wake: " + villager.getLocation());
        }
        String status = String.join("\n", run("smarttick status"));
        assertTrue(status.contains("the server keeps up"), status);
    }

    void aChunkLoadedWithASleeperWakesItWhenCaughtUp() {
        savedAsleep(cellC);
        server.getPluginManager().callEvent(new EntitiesLoadEvent(world.getChunkAt(0, 0), List.<Entity>of(cellC)));
        assertTrue(awake(cellC), "a villager loaded asleep while the server keeps up wakes as it loads");
    }

    void batchesLimitHowManyChangeEachSecond() throws Exception {
        List<FakeVillager> row = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            int x = 32 + (i % 15);
            int z = 2 + 4 * (i / 15);
            block(x, 64, z + 1, Material.COMPOSTER);
            FakeVillager villager = FakeVillager.spawn(server, new Location(world, x + 0.5, 64, z + 0.5));
            villager.setProfession(Villager.Profession.LIBRARIAN);
            villager.setMemory(MemoryKey.JOB_SITE, new Location(world, x, 64, z + 1));
            row.add(villager);
        }
        // 20 a second is one a tick; standing still for 0 minutes makes every villager with a job site eligible.
        configure(with(BEHIND, "batch-per-second", 20, "stationary-minutes", 0.0));
        ticks(10);
        long first = row.stream().filter(SmartTickScenarios::asleep).count();
        assertTrue(first >= 1 && first <= 10, "at most one villager a tick falls asleep, not all at once: " + first);
        ticks(60);
        assertEquals(30, row.stream().filter(SmartTickScenarios::asleep).count(), "then every one of them");
        configure(with(CAUGHT_UP, "batch-per-second", 20, "stationary-minutes", 0.0));
        ticks(10);
        long stillAsleep = row.stream().filter(SmartTickScenarios::asleep).count();
        assertTrue(stillAsleep >= 20 && stillAsleep < 30, "they wake a batch at a time too: " + stillAsleep);
        configure(with(BEHIND, "batch-per-second", 1000, "stationary-minutes", 5.0));
        ticks(45);
    }

    void reloadingKeepsSleepersAndSwitchingOffWakesThem() throws Exception {
        assertTrue(asleep(cellB) && asleep(cellC) && asleep(inCart));
        configure(with(BEHIND, "batch-per-second", 1000, "stationary-minutes", 5.0));
        assertTrue(asleep(cellB) && asleep(cellC) && asleep(inCart), "a reload hands the sleepers to the next start, nobody wakes");
        configure(with(BEHIND, "enabled", false));
        assertTrue(awake(cellB) && awake(cellC) && awake(inCart), "switching the plugin off with a reload wakes every sleeper");
        assertFalse(otherPlugins.isAware(), "but not another plugin's villager");
        String status = String.join("\n", run("smarttick status"));
        assertTrue(status.contains("off"), status);
        configure(with(BEHIND, "enabled", true));
        ticks(45);
        assertTrue(asleep(cellB) && asleep(cellC) && asleep(inCart), "switched on again, they sleep again");
    }

    void disablingThePluginWakesEverybody() {
        long sleepers = server.getEntities().stream().filter(entity -> entity instanceof Villager villager && asleep(villager)).count();
        assertTrue(sleepers >= 3, "villagers are asleep before the plugin stops: " + sleepers);
        server.getPluginManager().disablePlugin(plugin);
        for (var entity : server.getEntities()) {
            if (entity instanceof Villager villager && villager != otherPlugins) {
                assertTrue(awake(villager), "stopping the plugin wakes every villager it put to sleep: " + villager.getLocation());
            }
        }
        assertFalse(otherPlugins.isAware(), "another plugin's villager stays as it was");
    }
}

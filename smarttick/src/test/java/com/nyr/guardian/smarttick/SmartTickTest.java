package com.nyr.guardian.smarttick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.nyr.guardian.common.WorldFilter;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.entity.minecart.RideableMinecart;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.metadata.FixedMetadataValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SmartTickTest {

    private static final long HOLD = 5_000;
    private ServerMock server;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void scenariosOnTheCompiledClasses() throws Exception {
        SmartTickPlugin plugin = MockBukkit.load(SmartTickPlugin.class);
        new SmartTickScenarios(server, plugin).all();
    }

    // ---- the load and its thresholds ----

    @Test
    void theStateChangesOnlyAfterAWholeHoldEitherWay() {
        Hysteresis state = new Hysteresis(false);
        assertFalse(state.update(Settings.Pressure.HIGH, 0, HOLD));
        assertFalse(state.update(Settings.Pressure.HIGH, 4_999, HOLD));
        assertFalse(state.behind(), "not behind before the hold is over");
        assertTrue(state.update(Settings.Pressure.HIGH, 5_000, HOLD));
        assertTrue(state.behind(), "behind after five seconds at or past the sleep threshold");
        assertFalse(state.update(Settings.Pressure.BETWEEN, 6_000, HOLD));
        assertTrue(state.behind(), "a reading between the thresholds keeps the state");
        state.update(Settings.Pressure.LOW, 7_000, HOLD);
        state.update(Settings.Pressure.BETWEEN, 9_000, HOLD);
        state.update(Settings.Pressure.LOW, 10_000, HOLD);
        assertFalse(state.update(Settings.Pressure.LOW, 14_999, HOLD));
        assertTrue(state.behind(), "a break in the low readings restarts the hold");
        assertTrue(state.update(Settings.Pressure.LOW, 15_000, HOLD));
        assertFalse(state.behind(), "recovered after five seconds at or past the wake threshold");
        assertFalse(state.update(Settings.Pressure.UNKNOWN, 99_000, HOLD));
        assertFalse(state.behind(), "no reading changes nothing");
    }

    @Test
    void oneSpikeDoesNothing() {
        Hysteresis state = new Hysteresis(false);
        state.update(Settings.Pressure.HIGH, 0, HOLD);
        state.update(Settings.Pressure.LOW, 1_000, HOLD);
        state.update(Settings.Pressure.HIGH, 5_500, HOLD);
        assertFalse(state.behind(), "a spike followed by a good reading never counts as behind");
        assertFalse(state.update(Settings.Pressure.HIGH, 10_499, HOLD));
        assertTrue(state.update(Settings.Pressure.HIGH, 10_500, HOLD), "only a full hold of high readings does");
    }

    @Test
    void villagersThatAreTheLoadItselfSleepLongerAfterEachRelapse() {
        Hysteresis state = new Hysteresis(false);
        long first = Hysteresis.FIRST_BACKOFF_MILLIS;
        state.update(Settings.Pressure.HIGH, 0, HOLD);
        assertTrue(state.update(Settings.Pressure.HIGH, HOLD, HOLD));
        state.update(Settings.Pressure.LOW, 6_000, HOLD);
        assertTrue(state.update(Settings.Pressure.LOW, 11_000, HOLD), "the first sleep ends as soon as the readings allow");
        // behind again 30 s after waking: the villagers were the load, so the next sleep lasts at least two minutes
        state.update(Settings.Pressure.HIGH, 41_000, HOLD);
        assertTrue(state.update(Settings.Pressure.HIGH, 46_000, HOLD));
        assertEquals(first, state.minimumSleepMillis());
        state.update(Settings.Pressure.LOW, 47_000, HOLD);
        assertFalse(state.update(Settings.Pressure.LOW, 52_000, HOLD), "good readings, but the two minutes are not over");
        assertTrue(state.heldFor(52_000) > 0);
        assertTrue(state.update(Settings.Pressure.LOW, 46_000 + first, HOLD), "then it wakes");
        // a second relapse: four minutes
        long again = 46_000 + first + 60_000;
        state.update(Settings.Pressure.HIGH, again, HOLD);
        assertTrue(state.update(Settings.Pressure.HIGH, again + HOLD, HOLD));
        assertEquals(2 * first, state.minimumSleepMillis());
        state.update(Settings.Pressure.LOW, again + HOLD + 1000, HOLD);
        assertTrue(state.update(Settings.Pressure.LOW, again + HOLD + 2 * first, HOLD));
        // a recovery that holds past the relapse window forgets it
        long later = again + HOLD + 2 * first + Hysteresis.RELAPSE_MILLIS + 1000;
        state.update(Settings.Pressure.HIGH, later, HOLD);
        assertTrue(state.update(Settings.Pressure.HIGH, later + HOLD, HOLD));
        assertEquals(0, state.minimumSleepMillis());
    }

    @Test
    void thresholdsClassifyTickTimesAndTps() {
        Settings settings = settings(0, 5, 1000);
        assertEquals(Settings.Pressure.HIGH, settings.classify(new Load.Reading(40, Load.Unit.MSPT)));
        assertEquals(Settings.Pressure.BETWEEN, settings.classify(new Load.Reading(37.5, Load.Unit.MSPT)));
        assertEquals(Settings.Pressure.LOW, settings.classify(new Load.Reading(35, Load.Unit.MSPT)));
        assertEquals(Settings.Pressure.HIGH, settings.classify(new Load.Reading(18.99, Load.Unit.TPS)));
        assertEquals(Settings.Pressure.BETWEEN, settings.classify(new Load.Reading(19.5, Load.Unit.TPS)));
        assertEquals(Settings.Pressure.LOW, settings.classify(new Load.Reading(19.8, Load.Unit.TPS)));
        assertEquals(Settings.Pressure.UNKNOWN, settings.classify(null));
    }

    @Test
    void contradictingThresholdsAreCorrectedAndNamed() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader("""
            mspt: {sleep-at: 30, wake-at: 45}
            tps: {sleep-below: 19.5, wake-at: 18}
            scan-seconds: 0
            batch-per-second: 0
            hold-seconds: -3
            """));
        Settings settings = Settings.from(yaml);
        assertEquals(30, settings.msptWakeAt(), "wake-at above sleep-at would flip villagers every scan");
        assertEquals(19.5, settings.tpsWakeAt());
        assertEquals(1000, settings.scanMillis());
        assertEquals(1, settings.batchPerSecond());
        assertEquals(0, settings.holdMillis());
        assertEquals(4, settings.problems().size(), settings.problems().toString());
    }

    @Test
    void measuredTpsCountsTicksAndSeesAStall() {
        AtomicLong nanos = new AtomicLong();
        Loads.MeasuredTps tps = new Loads.MeasuredTps(nanos::get);
        assertNull(tps.read(null), "no reading before two ticks");
        for (int i = 0; i < 200; i++) {
            tps.tick();
            nanos.addAndGet(50_000_000L);
        }
        assertEquals(20.0, tps.read(null).value(), 0.01, "50 ms ticks read as 20 TPS");
        nanos.addAndGet(3_000_000_000L);
        tps.tick();
        assertTrue(tps.read(null).value() < 10, "a three-second stall reads as low TPS, not as no reading: " + tps.read(null).value());
        for (int i = 0; i < 100; i++) {
            nanos.addAndGet(100_000_000L);
            tps.tick();
        }
        assertEquals(10.0, tps.read(null).value(), 0.25, "100 ms ticks read as 10 TPS");
    }

    // ---- which villagers may sleep ----

    private static Settings settings(long holdMillis, long stationaryMinutes, int batch) {
        return new Settings(40, 35, 19, 19.8, holdMillis, 1000, batch, stationaryMinutes * 60_000, true, true, List.of());
    }

    private FakeVillager walled(TestWorld world, int x, int z) {
        for (int y = 64; y <= 65; y++) {
            world.getBlockAt(x - 1, y, z).setType(Material.GLASS);
            world.getBlockAt(x + 1, y, z).setType(Material.GLASS);
            world.getBlockAt(x, y, z - 1).setType(Material.GLASS);
        }
        world.getBlockAt(x, 64, z + 1).setType(Material.LECTERN);
        world.getBlockAt(x, 66, z).setType(Material.GLASS);
        FakeVillager villager = FakeVillager.spawn(server, new Location(world, x + 0.5, 64, z + 0.5));
        villager.setProfession(Villager.Profession.LIBRARIAN);
        villager.setMemory(MemoryKey.JOB_SITE, new Location(world, x, 64, z + 1));
        return villager;
    }

    private static Eligibility.Reason reason(Eligibility eligibility, Villager villager) {
        return eligibility.check(villager, 0, 0, 1_000).reason();
    }

    @Test
    void eachEligibilityRule() {
        MockBukkit.load(SmartTickPlugin.class);
        TestWorld world = new TestWorld("rules");
        server.addWorld(world);
        Eligibility eligibility = new Eligibility(WorldFilter.everywhere(), true, 5 * 60_000);
        FakeVillager villager = walled(world, 5, 5);
        assertEquals(Eligibility.Reason.ENCLOSED, reason(eligibility, villager), "walled in with its lectern in front");
        assertEquals(Eligibility.Reason.STILL, eligibility.check(villager, 5 * 60_000, 0, 1_000).reason(), "or still for five minutes");

        world.getBlockAt(5, 66, 5).setType(Material.AIR);
        assertEquals(Eligibility.Reason.FREE, reason(eligibility, villager), "without a roof it can jump onto the lectern and out");
        world.getBlockAt(5, 65, 6).setType(Material.GLASS);
        world.getBlockAt(4, 65, 5).setType(Material.AIR);
        assertEquals(Eligibility.Reason.FREE, reason(eligibility, villager), "or onto a wall one block high");
        world.getBlockAt(4, 64, 5).setType(Material.OAK_FENCE);
        assertEquals(Eligibility.Reason.ENCLOSED, reason(eligibility, villager), "but nothing jumps onto a fence");
        world.getBlockAt(4, 64, 5).setType(Material.GLASS);
        world.getBlockAt(4, 65, 5).setType(Material.GLASS);
        world.getBlockAt(5, 65, 6).setType(Material.AIR);
        world.getBlockAt(5, 66, 5).setType(Material.GLASS);
        world.getBlockAt(4, 64, 5).setType(Material.OAK_DOOR);
        world.getBlockAt(4, 65, 5).setType(Material.OAK_DOOR);
        assertEquals(Eligibility.Reason.FREE, reason(eligibility, villager), "villagers open wooden doors");
        world.getBlockAt(4, 64, 5).setType(Material.IRON_DOOR);
        world.getBlockAt(4, 65, 5).setType(Material.IRON_DOOR);
        assertEquals(Eligibility.Reason.ENCLOSED, reason(eligibility, villager), "but not iron doors");
        world.getBlockAt(4, 64, 5).setType(Material.AIR);
        assertEquals(Eligibility.Reason.ENCLOSED, reason(eligibility, villager), "a one-block gap at the feet is too low");
        world.getBlockAt(4, 65, 5).setType(Material.AIR);
        assertEquals(Eligibility.Reason.FREE, reason(eligibility, villager), "a two-block gap lets it out");
        world.getBlockAt(4, 64, 5).setType(Material.GLASS);
        world.getBlockAt(4, 65, 5).setType(Material.GLASS);

        RideableMinecart cart = world.spawn(new Location(world, 20.5, 64, 20.5), RideableMinecart.class);
        FakeVillager seated = FakeVillager.spawn(server, new Location(world, 20.5, 64, 20.5));
        seated.setProfession(Villager.Profession.FARMER);
        world.getBlockAt(21, 64, 20).setType(Material.COMPOSTER);
        seated.setMemory(MemoryKey.JOB_SITE, new Location(world, 21, 64, 20));
        cart.addPassenger(seated);
        assertEquals(Eligibility.Reason.IN_VEHICLE, reason(eligibility, seated), "a villager in a minecart cannot walk away");
        PlayerMock rider = server.addPlayer("Rider");
        cart.addPassenger(rider);
        assertEquals(Eligibility.Reason.WITH_PLAYER, reason(eligibility, seated), "unless a player rides along");

        villager.setMemory(MemoryKey.HOME, new Location(world, 5, 64, 9));
        assertEquals(Eligibility.Reason.BED, reason(eligibility, villager), "a villager with a bed keeps its brain");
        assertEquals(Eligibility.Reason.ENCLOSED, reason(new Eligibility(WorldFilter.everywhere(), false, 300_000), villager),
            "unless skip-villagers-with-beds is off");
        villager.setMemory(MemoryKey.HOME, null);
        world.getBlockAt(5, 64, 6).setType(Material.AIR);
        assertEquals(Eligibility.Reason.JOB_SITE_GONE, reason(eligibility, villager), "its job site block was broken");
        world.getBlockAt(5, 64, 6).setType(Material.LECTERN);
        villager.setMemory(MemoryKey.JOB_SITE, null);
        assertEquals(Eligibility.Reason.NO_JOB_SITE, reason(eligibility, villager));
        villager.setMemory(MemoryKey.JOB_SITE, new Location(world, 5, 64, 6));
        villager.setTrader(rider);
        assertEquals(Eligibility.Reason.TRADING, reason(eligibility, villager));
        villager.setTrader(null);
        assertEquals(Eligibility.Reason.JUST_TRADED, eligibility.check(villager, 0, 2_000, 1_000).reason());
        villager.setLeashedForTest(true);
        assertEquals(Eligibility.Reason.LEASHED, reason(eligibility, villager));
        villager.setLeashedForTest(false);
        villager.sleep(new Location(world, 5, 64, 9));
        assertEquals(Eligibility.Reason.IN_BED, reason(eligibility, villager));
        villager.wakeup();
        villager.setProfession(Villager.Profession.NONE);
        assertEquals(Eligibility.Reason.NO_PROFESSION, reason(eligibility, villager));
        villager.setProfession(Villager.Profession.NITWIT);
        assertEquals(Eligibility.Reason.NITWIT, reason(eligibility, villager));
        villager.setProfession(Villager.Profession.LIBRARIAN);
        villager.setBaby();
        assertEquals(Eligibility.Reason.BABY, reason(eligibility, villager));
        villager.setAdult();
        villager.setAI(false);
        assertEquals(Eligibility.Reason.NO_AI, reason(eligibility, villager));
        villager.setAI(true);
        org.bukkit.plugin.Plugin npcs = MockBukkit.createMockPlugin();
        villager.setMetadata("NPC", new FixedMetadataValue(npcs, true));
        assertEquals(Eligibility.Reason.NPC, reason(eligibility, villager), "another plugin's NPC");
        villager.removeMetadata("NPC", npcs);
        YamlConfiguration worlds = YamlConfiguration.loadConfiguration(new StringReader("worlds: {enabled: ['*'], disabled: [Rules]}"));
        Eligibility elsewhere = new Eligibility(WorldFilter.from(worlds.getConfigurationSection("worlds")), true, 300_000);
        assertEquals(Eligibility.Reason.WORLD, reason(elsewhere, seated), "a switched-off world is left alone");
    }

    // ---- the engine ----

    /** An engine judging villagers inline, with a clock the test moves. */
    private Engine engine(SmartTickPlugin plugin, Load load, Settings settings, AtomicLong clock) {
        return new Engine(plugin, settings, load, new Hysteresis(false), new Tracker(), new Engine.Totals(),
            new Eligibility(WorldFilter.everywhere(), true, settings.stationaryMillis()), new Restocker(Villager.class), clock::get, true);
    }

    private static void run(Engine engine, AtomicLong clock, long millis) {
        for (long t = 0; t < millis; t += 50) {
            clock.addAndGet(50);
            engine.tick();
        }
    }

    private static boolean asleep(Villager villager) {
        return !villager.isAware() && Marks.asleep(villager);
    }

    @Test
    void eachRegionDecidesForTheVillagersInIt() {
        SmartTickPlugin plugin = MockBukkit.load(SmartTickPlugin.class);
        TestWorld world = new TestWorld("regions");
        server.addWorld(world);
        FakeVillager east = walled(world, 5, 5);
        FakeVillager west = walled(world, -11, 5);
        AtomicReference<Double> eastMillis = new AtomicReference<>(60.0);
        Load regions = new Load() {
            @Override
            public boolean global() {
                return false;
            }

            @Override
            public Reading read(Location where) {
                return new Reading(where.getX() >= 0 ? eastMillis.get() : 5.0, Unit.MSPT);
            }

            @Override
            public String source() {
                return "two test regions";
            }
        };
        AtomicLong clock = new AtomicLong();
        Engine engine = engine(plugin, regions, settings(HOLD, 5, 1000), clock);
        run(engine, clock, 3_000);
        assertFalse(asleep(east), "a region behind for three seconds is not behind yet");
        run(engine, clock, 4_000);
        assertTrue(asleep(east), "the villager in the region behind sleeps once the hold is over");
        assertFalse(asleep(west), "the villager in the region keeping up stays awake");
        eastMillis.set(37.0);
        run(engine, clock, 8_000);
        assertTrue(asleep(east), "between the thresholds it stays asleep");
        eastMillis.set(20.0);
        run(engine, clock, 3_000);
        assertTrue(asleep(east), "caught up for three seconds is not caught up yet");
        run(engine, clock, 4_000);
        assertFalse(asleep(east), "the region caught up, its villager wakes");
    }

    @Test
    void aVillagerLoadedAsleepWakesAtOnceWhereItsRegionKeepsUp() {
        SmartTickPlugin plugin = MockBukkit.load(SmartTickPlugin.class);
        TestWorld world = new TestWorld("loaded");
        server.addWorld(world);
        FakeVillager east = walled(world, 5, 5);
        FakeVillager west = walled(world, -11, 5);
        Marks.sleep(east);
        Marks.sleep(west);
        Load regions = new Load() {
            @Override
            public boolean global() {
                return false;
            }

            @Override
            public Reading read(Location where) {
                return new Reading(where.getX() >= 0 ? 60.0 : 5.0, Unit.MSPT);
            }

            @Override
            public String source() {
                return "a region behind in the east, one keeping up in the west";
            }
        };
        Engine engine = engine(plugin, regions, settings(HOLD, 5, 1000), new AtomicLong());
        assertFalse(engine.keepAsleepOnLoad(west), "loaded asleep in a region keeping up, it wakes as it loads, without a hold");
        assertTrue(engine.keepAsleepOnLoad(east), "loaded asleep in a region still behind, it stays asleep");
    }

    @Test
    void aTradedVillagerStaysAwakeAWhileThenSleepsAgain() {
        SmartTickPlugin plugin = MockBukkit.load(SmartTickPlugin.class);
        TestWorld world = new TestWorld("trades");
        server.addWorld(world);
        FakeVillager villager = walled(world, 5, 5);
        Load behind = new Load() {
            @Override
            public boolean global() {
                return true;
            }

            @Override
            public Reading read(Location where) {
                return new Reading(80, Unit.MSPT);
            }

            @Override
            public String source() {
                return "always behind";
            }
        };
        AtomicLong clock = new AtomicLong();
        Engine engine = engine(plugin, behind, settings(0, 5, 1000), clock);
        run(engine, clock, 1_500);
        assertTrue(asleep(villager));
        engine.traded(villager);
        assertFalse(asleep(villager), "opening the trades wakes it at once");
        run(engine, clock, 9_000);
        assertFalse(asleep(villager), "it stays awake while its brain levels it up and settles");
        run(engine, clock, 2_500);
        assertTrue(asleep(villager), "then sleeps again, the server still being behind");
    }

    @Test
    void breakingASleepersJobSiteWakesIt() {
        SmartTickPlugin plugin = MockBukkit.load(SmartTickPlugin.class);
        TestWorld world = new TestWorld("lecterns");
        server.addWorld(world);
        FakeVillager librarian = walled(world, 5, 5);
        FakeVillager neighbour = walled(world, 9, 5);
        Marks.sleep(librarian);
        Marks.sleep(neighbour);
        PlayerMock player = server.addPlayer("Reroller");
        server.getPluginManager().callEvent(new BlockBreakEvent(world.getBlockAt(5, 64, 6), player));
        assertFalse(asleep(librarian), "the librarian whose lectern broke wakes, so it can drop its profession and reroll");
        assertTrue(asleep(neighbour), "a sleeper linked to another lectern stays asleep");
        assertTrue(plugin.isEnabled());
    }

    @Test
    void aMarkedMobThatIsNoVillagerWakesAsItLoadsOrTransforms() {
        MockBukkit.load(SmartTickPlugin.class);
        TestWorld world = new TestWorld("marks");
        server.addWorld(world);
        org.bukkit.entity.Zombie zombie = world.spawn(new Location(world, 3.5, 64, 3.5), org.bukkit.entity.Zombie.class);
        Marks.sleep(zombie);
        server.getPluginManager().callEvent(new org.bukkit.event.world.EntitiesLoadEvent(world.getChunkAt(0, 0),
            List.<org.bukkit.entity.Entity>of(zombie)));
        assertTrue(zombie.isAware() && !Marks.asleep(zombie), "a mob that carried the mark out of a villager wakes as it loads");
        FakeVillager villager = walled(world, 9, 9);
        Marks.sleep(villager);
        org.bukkit.entity.Witch witch = world.spawn(new Location(world, 9.5, 64, 9.5), org.bukkit.entity.Witch.class);
        Marks.sleep(witch);
        server.getPluginManager().callEvent(new org.bukkit.event.entity.EntityTransformEvent(villager,
            List.<org.bukkit.entity.Entity>of(witch), org.bukkit.event.entity.EntityTransformEvent.TransformReason.LIGHTNING));
        assertTrue(witch.isAware() && !Marks.asleep(witch), "a sleeping villager struck by lightning does not leave a sleeping witch");
    }

    @Test
    void theServersOwnRestockRunsWhereItExists() {
        MockBukkit.load(SmartTickPlugin.class);
        TestWorld world = new TestWorld("restock");
        server.addWorld(world);
        FakeVillager villager = walled(world, 5, 5);
        MerchantRecipe book = new MerchantRecipe(new ItemStack(Material.ENCHANTED_BOOK), 7, 12, true, 5, 0.2f, 0, 0);
        book.addIngredient(new ItemStack(Material.EMERALD, 30));
        villager.setRecipes(List.of(book));
        world.setFullTime(4_000);
        Restocker newer = new Restocker(RestockingVillager.class);
        assertTrue(newer.serverRestock());
        assertTrue(newer.maybeRestock(villager, villager.getMemory(MemoryKey.JOB_SITE)));
        assertEquals(1, villager.serverRestocks, "the server's restock() ran");
        assertEquals(0, villager.getRecipe(0).getUses());
        assertFalse(new Restocker(Villager.class).serverRestock(), "the 1.21.1 compile API has no restock(): trades are restocked one by one");
        villager.getRecipe(0).setUses(2);
        villager.teleport(new Location(world, 5.5, 64, 2.5));
        world.setFullTime(24_000 + 4_000);
        assertFalse(newer.maybeRestock(villager, villager.getMemory(MemoryKey.JOB_SITE)), "not away from its job site");
    }
}

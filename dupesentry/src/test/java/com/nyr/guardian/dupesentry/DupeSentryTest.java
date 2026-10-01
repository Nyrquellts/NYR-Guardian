package com.nyr.guardian.dupesentry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import com.nyr.guardian.common.Alerts;
import com.nyr.guardian.common.Messages;
import com.nyr.guardian.common.WorldFilter;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DupeSentryTest {

    private ServerMock server;

    @TempDir
    Path dir;

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void scenariosOnTheCompiledClasses() {
        DupeSentryPlugin plugin = MockBukkit.load(DupeSentryPlugin.class);
        new DupeSentryScenarios(server, plugin).all();
    }

    /** Tasks handed to "the next tick at a location", run when the test says so. */
    private static final class NextTick {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Location> places = new ArrayList<>();

        void add(Location place, Runnable task) {
            places.add(place);
            tasks.add(task);
        }

        void run() {
            List<Runnable> now = new ArrayList<>(tasks);
            tasks.clear();
            now.forEach(Runnable::run);
        }
    }

    @Test
    void tickMemoryForgetsOnTheNextTickAtItsPlaceAndKeepsWorldsApart() {
        WorldMock one = server.addSimpleWorld("one");
        WorldMock two = server.addSimpleWorld("two");
        NextTick next = new NextTick();
        TickMemory memory = new TickMemory(next::add);
        TickMemory.Spot here = TickMemory.Spot.of(one.getBlockAt(1, 64, 1));
        TickMemory.Mark first = new TickMemory.Mark(Material.TNT);
        Location piston = new Location(one, 0, 64, 1);

        assertTrue(memory.isEmpty());
        memory.remember(Map.of(here, first), piston);
        assertSame(first, memory.get(here));
        assertNull(memory.get(TickMemory.Spot.of(two.getBlockAt(1, 64, 1))), "the same coordinates in another world are another spot");
        assertEquals(List.of(piston), next.places, "the clean-up runs where the piston is, on the region that owns it");

        // A second push of the same spot, remembered before the first clean-up ran, is not forgotten by it.
        TickMemory.Mark second = new TickMemory.Mark(Material.TNT);
        List<Runnable> firstCleanUp = new ArrayList<>(next.tasks);
        next.tasks.clear();
        memory.remember(Map.of(here, second), piston);
        firstCleanUp.forEach(Runnable::run);
        assertSame(second, memory.get(here), "the first push's clean-up leaves the second push's mark alone");
        next.run();
        assertNull(memory.get(here));
        assertTrue(memory.isEmpty(), "everything is forgotten one tick later");

        List<Map<TickMemory.Spot, TickMemory.Mark>> expired = new ArrayList<>();
        memory.remember(Map.of(here, new TickMemory.Mark(Material.TRIPWIRE_HOOK)), piston, expired::add);
        next.run();
        assertEquals(1, expired.size(), "a batch can ask to see what it remembered when it is forgotten");
        memory.remember(Map.of(), piston);
        assertTrue(next.tasks.isEmpty(), "nothing to remember schedules nothing");
    }

    private static File yaml(Path dir, String name, String text) throws Exception {
        File file = dir.resolve(name).toFile();
        Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void paperGlobalYmlSaysWhichDupesPaperAlreadyBlocks() throws Exception {
        Map<Guard, ServerFixes.Verdict> defaults = ServerFixes.fromFile(yaml(dir, "defaults.yml", """
            unsupported-settings:
              allow-headless-pistons: false
              allow-piston-duplication: false
              allow-unsafe-end-portal-teleportation: false
              skip-tripwire-hook-placement-validation: false
            """));
        assertEquals(ServerFixes.Verdict.BLOCKS, defaults.get(Guard.PISTON_DUPES));
        assertEquals(ServerFixes.Verdict.BLOCKS, defaults.get(Guard.PORTAL_GRAVITY));
        assertEquals(ServerFixes.Verdict.BLOCKS, defaults.get(Guard.TRIPWIRE_HOOKS));
        assertFalse(defaults.containsKey(Guard.CONTAINER_DESYNC), "Paper has no setting for container screens");

        Map<Guard, ServerFixes.Verdict> unsafe = ServerFixes.fromFile(yaml(dir, "unsafe.yml", """
            unsupported-settings:
              allow-piston-duplication: true
              allow-unsafe-end-portal-teleportation: true
            """));
        assertEquals(ServerFixes.Verdict.ALLOWS, unsafe.get(Guard.PISTON_DUPES));
        assertEquals(ServerFixes.Verdict.ALLOWS, unsafe.get(Guard.PORTAL_GRAVITY));
        assertEquals(ServerFixes.Verdict.ALLOWS, unsafe.get(Guard.TRIPWIRE_HOOKS), "Paper 1.20.6 has no hook validation setting at all");

        Map<Guard, ServerFixes.Verdict> missing = ServerFixes.fromFile(dir.resolve("absent.yml").toFile());
        assertEquals(ServerFixes.Verdict.UNKNOWN, missing.get(Guard.PISTON_DUPES), "an unreadable Paper config leaves the guard idle");
        assertEquals(ServerFixes.Verdict.UNKNOWN, missing.get(Guard.PORTAL_GRAVITY));

        ServerFixes spigot = ServerFixes.none("Spigot");
        for (Guard guard : Guard.values()) {
            assertTrue(spigot.needed(guard), "Spigot blocks none of them: " + guard);
        }
    }

    @Test
    void aGuardStaysIdleWhereTheServerBlocksItsDupe() throws Exception {
        DupeSentryPlugin plugin = MockBukkit.load(DupeSentryPlugin.class);
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock staff = server.addPlayer("Staff");
        staff.setOp(true);
        plugin.useServerFixes(new ServerFixes("Paper", Map.of(Guard.PISTON_DUPES, ServerFixes.Verdict.BLOCKS,
            Guard.PORTAL_GRAVITY, ServerFixes.Verdict.UNKNOWN, Guard.TRIPWIRE_HOOKS, ServerFixes.Verdict.ALLOWS)));
        plugin.reload(server.getConsoleSender());

        Block piston = world.getBlockAt(0, 64, 0);
        piston.setType(Material.PISTON);
        Block tnt = world.getBlockAt(1, 64, 0);
        tnt.setType(Material.TNT);
        server.getPluginManager().callEvent(new BlockPistonExtendEvent(piston, List.of(), BlockFace.EAST));
        TNTPrimeEvent prime = new TNTPrimeEvent(tnt, TNTPrimeEvent.PrimeCause.REDSTONE, null, null);
        server.getPluginManager().callEvent(prime);
        assertFalse(prime.isCancelled(), "Paper's own fix moves the pushed block's real state; the guard must not act on top of it");
        assertTrue(plugin.pushedMemory().isEmpty(), "the idle guard does not even listen");

        while (staff.nextMessage() != null) {
            continue;
        }
        staff.performCommand("dupesentry status");
        List<String> lines = new ArrayList<>();
        for (String line = staff.nextMessage(); line != null; line = staff.nextMessage()) {
            lines.add(line.replaceAll("§.", ""));
        }
        String all = String.join(" | ", lines);
        assertTrue(all.contains("piston-dupes: idle: Paper blocks this dupe itself (unsupported-settings.allow-piston-duplication: false)"), all);
        assertTrue(all.contains("portal-gravity: idle: could not read unsupported-settings.allow-unsafe-end-portal-teleportation from this Paper server"), all);
        assertTrue(all.contains("tripwire-hooks: on"), all);
        assertTrue(all.contains("container-desync: on"), all);
    }

    /** The tripwire guard on its own, with the server's BlockData#isSupported answered by the test. */
    @Test
    void aHookPutBackAfterBreakingOffIsRemovedAndASecondDropCancelled() {
        WorldMock world = server.addSimpleWorld("world");
        org.bukkit.plugin.Plugin plugin = MockBukkit.createMockPlugin();
        NextTick next = new NextTick();
        TickMemory memory = new TickMemory(next::add);
        Set<Location> unsupported = new HashSet<>();
        Alerts alerts = new Alerts("test-alerts", "nyrdupesentry.alerts", quietLogger(new ArrayList<>()), null, List::of);
        Messages messages = bundledMessages();
        Reporter reporter = new Reporter(alerts, messages);
        TripwireGuard guard = new TripwireGuard(memory, WorldFilter.everywhere(), reporter, new DupeNames(messages), server::getWorld,
            block -> !unsupported.contains(block.getLocation()));
        server.getPluginManager().registerEvents(guard, plugin);

        // 1. The door behind the hook opened in the middle of the hook's own update: the hook breaks off and drops.
        Block hook = world.getBlockAt(10, 64, 10);
        hook.setType(Material.TRIPWIRE_HOOK);
        unsupported.add(hook.getLocation());
        assertFalse(hookDrop(world, hook), "the first drop of a hook that broke off is the real one");
        assertNotNull(memory.get(TickMemory.Spot.of(hook)));
        // The game put it back (still standing there, as the drop left it); on the next tick it is removed without a drop.
        next.run();
        assertEquals(Material.AIR, hook.getType(), "the hook the game put back is removed");
        assertEquals(1, reporter.count(Guard.TRIPWIRE_HOOKS));

        // 2. Put back and broken off again in the same tick: the second drop is the copy.
        hook.setType(Material.TRIPWIRE_HOOK);
        assertFalse(hookDrop(world, hook));
        assertTrue(hookDrop(world, hook), "a second drop of the same hook in the same tick is cancelled");
        hook.setType(Material.AIR);
        next.run();
        assertEquals(2, reporter.count(Guard.TRIPWIRE_HOOKS));

        // 3. A hook on a solid face that someone breaks is an ordinary break: nothing remembered.
        unsupported.clear();
        hook.setType(Material.TRIPWIRE_HOOK);
        assertFalse(hookDrop(world, hook));
        assertTrue(memory.isEmpty(), "a hook that could have stayed did not break off: nothing to guard");

        // 4. A player placing a new hook where one broke off keeps it.
        unsupported.add(hook.getLocation());
        assertFalse(hookDrop(world, hook));
        PlayerMock kai = server.addPlayer("Kai");
        BlockPlaceEvent place = new BlockPlaceEvent(hook, hook.getState(), world.getBlockAt(10, 64, 9), new ItemStack(Material.TRIPWIRE_HOOK),
            kai, true, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(place);
        next.run();
        assertEquals(Material.TRIPWIRE_HOOK, hook.getType(), "a hook a player placed is left alone");
        assertEquals(2, reporter.count(Guard.TRIPWIRE_HOOKS));

        // 5. A hook item turning up where no hook stands is none of its business.
        hook.setType(Material.AIR);
        assertFalse(hookDrop(world, hook));
        assertTrue(memory.isEmpty());
        alerts.close();
    }

    /** The messages section of the bundled config.yml. */
    private static Messages bundledMessages() {
        java.io.InputStream in = DupeSentryTest.class.getClassLoader().getResourceAsStream("nyr-dupesentry/config.yml");
        assertNotNull(in, "the bundled config.yml is on the test class path");
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        return new Messages(config.getConfigurationSection("messages"));
    }

    private static Logger quietLogger(List<String> into) {
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                into.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        return logger;
    }

    private boolean hookDrop(WorldMock world, Block hook) {
        ItemSpawnEvent event = new ItemSpawnEvent(DupeSentryScenarios.itemAt(server, hook.getLocation().clone().add(0.5, 0.3, 0.5),
            Material.TRIPWIRE_HOOK));
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void alertsNameTheNearestPlayerAndCoolDownPerChunk() {
        WorldMock world = server.addSimpleWorld("world");
        PlayerMock near = server.addPlayer("Near");
        near.teleport(new Location(world, 12, 64, 12));
        PlayerMock far = server.addPlayer("Far");
        far.teleport(new Location(world, 400, 64, 400));
        assertEquals("Near", Reporter.nearestPlayer(world, new Location(world, 10, 64, 10)));
        assertNull(Reporter.nearestPlayer(world, new Location(world, -500, 64, -500)), "nobody within 64 blocks");

        List<String> logged = new ArrayList<>();
        Alerts alerts = new Alerts("test-alerts", "nyrdupesentry.alerts", quietLogger(logged), null, List::of);
        Messages messages = bundledMessages();
        Reporter reporter = new Reporter(alerts, messages);
        DupeNames names = new DupeNames(messages);
        assertTrue(reporter.stopped(Guard.PISTON_DUPES, names.tnt(), new Location(world, 10, 64, 10)));
        assertFalse(reporter.stopped(Guard.PISTON_DUPES, names.tnt(), new Location(world, 11, 64, 10)), "the same duper again: counted, not re-sent");
        assertTrue(reporter.stopped(Guard.PISTON_DUPES, names.tnt(), new Location(world, 100, 64, 10)), "another chunk is another duper");
        assertTrue(reporter.stopped(Guard.PISTON_DUPES, names.pushedBlock(Material.RAIL), new Location(world, 10, 64, 10)), "another kind too");
        assertFalse(reporter.stopped(Guard.CONTAINER_DESYNC, names.container(), new Location(world, 10, 64, 10)), "screens are counted silently");
        assertEquals(4, reporter.count(Guard.PISTON_DUPES));
        assertEquals(1, reporter.count(Guard.CONTAINER_DESYNC));
        assertEquals("[DupeSentry] Stopped a TNT duper at world 10 64 10, nearest player Near", logged.get(0));
        assertEquals("[DupeSentry] Stopped a rail duper at world 10 64 10, nearest player Near", logged.get(2));
        alerts.close();
    }
}

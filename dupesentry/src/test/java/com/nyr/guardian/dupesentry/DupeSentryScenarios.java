package com.nyr.guardian.dupesentry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.FallingBlockMock;
import be.seeseemelk.mockbukkit.entity.ItemEntityMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import be.seeseemelk.mockbukkit.entity.StorageMinecartMock;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.opentest4j.TestAbortedException;

/**
 * What DupeSentry must do that MockBukkit can run, through Bukkit events only, on the compiled classes and on the built jar.
 * The events carry exactly what a server's own events carry during a piston push, a portal trip or a container break. The
 * dupes themselves are built block by block on live servers by testbed/scenarios/dupesentry.mjs; the tripwire guard needs
 * BlockData#isSupported, which MockBukkit does not implement, and is tested on its own class by DupeSentryTest.
 */
public final class DupeSentryScenarios {

    private static final Pattern STOPPED = Pattern.compile("(\\d+) (?:stopped|screens closed) since start");

    private final ServerMock server;
    private final Plugin plugin;
    private final World world;
    private final World end;
    private final World nether;
    private final World other;
    private final PlayerMock staff;

    public DupeSentryScenarios(ServerMock server, Plugin plugin) {
        this.server = server;
        this.plugin = plugin;
        this.world = server.getWorlds().isEmpty() ? server.addSimpleWorld("world") : server.getWorlds().get(0);
        this.end = server.createWorld(new WorldCreator("world_the_end").environment(World.Environment.THE_END));
        this.nether = server.createWorld(new WorldCreator("world_nether").environment(World.Environment.NETHER));
        this.other = server.addSimpleWorld("other");
        this.staff = server.addPlayer("Staff");
        staff.setOp(true);
    }

    public void all() {
        try {
            pistonTntAndDropsOnlyInTheTickOfThePush();
            pistonSwitchesInConfig();
            fallingBlocksStayOutOfEndPortals();
            containerScreensCloseWhenTheirContainerGoes();
            cursorItemGoesBackOnQuit();
            statusAndReload();
        } catch (TestAbortedException unimplemented) {
            fail("MockBukkit could not run part of the scenario, so it proved nothing: " + unimplemented.getMessage(), unimplemented);
        }
    }

    // --- piston-dupes ------------------------------------------------------------------------------------------------------

    private Block put(World in, int x, int y, int z, Material material) {
        Block block = in.getBlockAt(x, y, z);
        block.setType(material);
        return block;
    }

    /**
     * A block as a piston event lists it: MockBukkit does not implement Block#getPistonMoveReaction, so the block answers it
     * as the server does (a pumpkin breaks, TNT, carpets and rails move) and passes everything else to MockBukkit's block.
     */
    private static Block asListed(Block block) {
        PistonMoveReaction reaction = block.getType() == Material.PUMPKIN ? PistonMoveReaction.BREAK : PistonMoveReaction.MOVE;
        return (Block) java.lang.reflect.Proxy.newProxyInstance(Block.class.getClassLoader(), new Class<?>[] {Block.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getPistonMoveReaction")) {
                    return reaction;
                }
                try {
                    return method.invoke(block, args);
                } catch (java.lang.reflect.InvocationTargetException thrown) {
                    throw thrown.getCause();
                }
            });
    }

    private static List<Block> listed(Block... blocks) {
        List<Block> out = new ArrayList<>();
        for (Block block : blocks) {
            out.add(asListed(block));
        }
        return out;
    }

    private boolean primeCancelled(Block tnt) {
        TNTPrimeEvent event = new TNTPrimeEvent(tnt, TNTPrimeEvent.PrimeCause.REDSTONE, null, null);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    /** An item entity where a block would drop it, without the spawn event MockBukkit's dropItem already fires. */
    static Item itemAt(ServerMock server, Location at, Material material) {
        ItemEntityMock item = new ItemEntityMock(server, UUID.randomUUID(), new ItemStack(material));
        item.setLocation(at);
        return item;
    }

    private boolean dropCancelled(Block at, Material material) {
        ItemSpawnEvent event = new ItemSpawnEvent(itemAt(server, at.getLocation().clone().add(0.5, 0.3, 0.5), material));
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    void pistonTntAndDropsOnlyInTheTickOfThePush() {
        Block piston = put(world, 0, 70, 0, Material.PISTON);
        Block tnt = put(world, 1, 70, 0, Material.TNT);
        Block carpet = put(world, 1, 71, 0, Material.WHITE_CARPET);
        Block rail = put(world, 1, 72, 0, Material.RAIL);
        Block pumpkin = put(world, 2, 70, 0, Material.PUMPKIN);
        Block elsewhere = put(world, 30, 70, 0, Material.TNT);
        Block otherWorldTnt = put(other, 1, 70, 0, Material.TNT);
        long before = stopped("piston-dupes");

        assertFalse(primeCancelled(tnt), "TNT that no piston is pushing is lit as usual");
        server.getPluginManager().callEvent(new BlockPistonExtendEvent(piston, listed(tnt, carpet, rail, pumpkin), BlockFace.EAST));

        assertTrue(primeCancelled(tnt), "a pushed TNT lit in the tick of the push stays TNT");
        assertFalse(dropCancelled(pumpkin, Material.PUMPKIN), "a pumpkin the push breaks drops as always (melon and pumpkin farms)");
        assertTrue(dropCancelled(carpet, Material.WHITE_CARPET), "a pushed carpet breaking off does not drop a second carpet");
        assertTrue(dropCancelled(rail, Material.RAIL), "a pushed rail breaking off does not drop a second rail");
        assertFalse(dropCancelled(carpet, Material.DIAMOND), "other items at that spot are left alone");
        Block empty = put(world, 3, 70, 0, Material.AIR);
        server.getPluginManager().callEvent(new BlockPistonExtendEvent(piston, listed(empty), BlockFace.EAST));
        assertFalse(dropCancelled(empty, Material.WHITE_CARPET), "a carpet dropping where no pushed carpet stands is left alone");
        assertFalse(primeCancelled(elsewhere), "TNT the piston is not pushing is lit as usual");
        assertFalse(primeCancelled(otherWorldTnt), "the same spot in another world is not the pushed one");
        List<String> alerts = drain(staff);
        assertTrue(alerts.stream().anyMatch(line -> line.contains("Stopped a TNT duper at world 1 70 0")),
            "staff are told what was stopped and where: " + String.join(" | ", alerts));
        assertTrue(alerts.stream().anyMatch(line -> line.contains("Stopped a white carpet duper")), String.join(" | ", alerts));
        assertEquals(before + 3, stopped("piston-dupes"), "three dupes were stopped");

        server.getScheduler().performOneTick();
        assertFalse(primeCancelled(tnt), "on the next tick the spot is forgotten: TNT pushed now and lit later is untouched");
        assertFalse(dropCancelled(carpet, Material.WHITE_CARPET), "on the next tick a carpet may drop there again");

        BlockPistonRetractEvent pulled = new BlockPistonRetractEvent(piston, listed(tnt), BlockFace.WEST);
        server.getPluginManager().callEvent(pulled);
        assertTrue(primeCancelled(tnt), "a sticky piston pulling TNT is guarded the same way");
        server.getScheduler().performOneTick();

        BlockPistonExtendEvent blocked = new BlockPistonExtendEvent(piston, listed(tnt), BlockFace.EAST);
        blocked.setCancelled(true);
        server.getPluginManager().callEvent(blocked);
        assertFalse(primeCancelled(tnt), "a push another plugin cancelled moves nothing, so nothing is guarded");
        server.getScheduler().performOneTick();
        drain(staff);
    }

    void pistonSwitchesInConfig() {
        Block piston = put(world, 0, 80, 0, Material.PISTON);
        Block tnt = put(world, 1, 80, 0, Material.TNT);
        Block carpet = put(world, 1, 81, 0, Material.WHITE_CARPET);

        setGuardSetting("piston-dupes", "tnt", true, false);
        server.getPluginManager().callEvent(new BlockPistonExtendEvent(piston, listed(tnt, carpet), BlockFace.EAST));
        assertFalse(primeCancelled(tnt), "piston-dupes.tnt: false lets TNT dupers run (for world eaters)");
        assertTrue(dropCancelled(carpet, Material.WHITE_CARPET), "carpet dupers are still stopped");
        assertTrue(statusLine("piston-dupes").contains("on (carpets and rails)"), statusLine("piston-dupes"));
        server.getScheduler().performOneTick();

        setGuardSetting("piston-dupes", "tnt", false, true);
        setGuardEnabled("piston-dupes", false);
        server.getPluginManager().callEvent(new BlockPistonExtendEvent(piston, listed(tnt, carpet), BlockFace.EAST));
        assertFalse(primeCancelled(tnt), "piston-dupes.enabled: false switches the whole guard off");
        assertFalse(dropCancelled(carpet, Material.WHITE_CARPET), "carpets included");
        assertTrue(statusLine("piston-dupes").contains("off in config.yml"), statusLine("piston-dupes"));
        server.getScheduler().performOneTick();
        setGuardEnabled("piston-dupes", true);
        drain(staff);
    }

    // --- portal-gravity ----------------------------------------------------------------------------------------------------

    private boolean portalCancelled(Entity entity, World from, World to) {
        EntityPortalEvent event = new EntityPortalEvent(entity, new Location(from, 0.5, 64, 0.5), new Location(to, 100.5, 49, 0.5));
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    void fallingBlocksStayOutOfEndPortals() {
        FallingBlockMock sand = new FallingBlockMock(server, UUID.randomUUID());
        sand.setBlockState(put(world, 5, 90, 5, Material.SAND).getState());
        sand.setLocation(new Location(world, 5.5, 64, 5.5));
        Item item = itemAt(server, new Location(world, 5.5, 64, 5.5), Material.SAND);
        long before = stopped("portal-gravity");

        assertTrue(portalCancelled(sand, world, end), "falling sand does not go into the End through an end portal");
        assertTrue(portalCancelled(sand, end, world), "nor out of it through the exit portal");
        assertFalse(portalCancelled(sand, end, end), "end gateways keep falling blocks inside the End and copy nothing");
        assertFalse(portalCancelled(sand, world, nether), "nether portals are left alone by default");
        assertFalse(portalCancelled(item, world, end), "items and every other entity use end portals as usual");
        List<String> alerts = drain(staff);
        assertTrue(alerts.stream().anyMatch(line -> line.contains("Stopped a sand duplicated through an end portal")), String.join(" | ", alerts));
        assertEquals(before + 2, stopped("portal-gravity"));

        setGuardSetting("portal-gravity", "nether-portals", false, true);
        assertTrue(portalCancelled(sand, world, nether), "nether-portals: true keeps falling blocks out of nether portals too");
        setGuardSetting("portal-gravity", "nether-portals", true, false);
        item.remove();
        drain(staff);
    }

    // --- container-desync --------------------------------------------------------------------------------------------------

    private static final class Menu implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    void containerScreensCloseWhenTheirContainerGoes() {
        PlayerMock kai = server.addPlayer("Kai");
        PlayerMock luna = server.addPlayer("Luna");
        kai.teleport(new Location(world, 40.5, 64, 40.5));
        long before = stopped("container-desync");

        Block chestBlock = put(world, 41, 64, 41, Material.CHEST);
        Inventory chest = ((Chest) chestBlock.getState()).getInventory();
        kai.openInventory(chest);
        assertTrue(chest.getViewers().contains(kai));
        server.getPluginManager().callEvent(new BlockBreakEvent(chestBlock, luna));
        assertFalse(chest.getViewers().contains(kai), "Kai's chest screen closes the moment Luna breaks the chest");

        Menu menu = new Menu();
        menu.inventory = server.createInventory(menu, 9, "menu");
        kai.openInventory(menu.inventory);
        server.getPluginManager().callEvent(new BlockBreakEvent(chestBlock, luna));
        server.getPluginManager().callEvent(new ChunkUnloadEvent(world.getChunkAt(chestBlock)));
        assertTrue(menu.inventory.getViewers().contains(kai), "another plugin's menu belongs to no block and is left open");
        kai.closeInventory();

        Block secondBlock = put(world, 43, 64, 41, Material.CHEST);
        Inventory second = ((Chest) secondBlock.getState()).getInventory();
        kai.openInventory(second);
        callTeleport(kai, new Location(world, 44.5, 64, 43.5));
        assertTrue(second.getViewers().contains(kai), "a short teleport keeps the screen open");
        callTeleport(kai, new Location(world, 80.5, 64, 80.5));
        assertFalse(second.getViewers().contains(kai), "a teleport more than 8 blocks away closes it");

        kai.openInventory(second);
        server.getPluginManager().callEvent(new ChunkUnloadEvent(world.getChunkAt(secondBlock)));
        assertFalse(second.getViewers().contains(kai), "the chunk of the container unloading closes it");

        StorageMinecartMock minecart = new StorageMinecartMock(server, UUID.randomUUID());
        minecart.setLocation(new Location(world, 42.5, 64, 44.5));
        kai.openInventory(minecart.getInventory());
        assertTrue(minecart.getInventory().getViewers().contains(kai));
        server.getPluginManager().callEvent(new VehicleDestroyEvent(minecart, luna));
        assertFalse(minecart.getInventory().getViewers().contains(kai), "a chest minecart's screen closes when the minecart is destroyed");

        assertTrue(drain(staff).stream().noneMatch(line -> line.contains("Stopped")), "closing a screen is no dupe: no staff alert");
        assertEquals(before + 4, stopped("container-desync"), "four screens were closed");
    }

    private void callTeleport(PlayerMock player, Location to) {
        server.getPluginManager().callEvent(new PlayerTeleportEvent(player, player.getLocation(), to));
    }

    void cursorItemGoesBackOnQuit() {
        PlayerMock mira = server.addPlayer("Mira");
        mira.getInventory().clear();
        mira.setItemOnCursor(new ItemStack(Material.DIAMOND, 3));
        server.getPluginManager().callEvent(new PlayerQuitEvent(mira, "Mira left"));
        assertTrue(mira.getItemOnCursor() == null || mira.getItemOnCursor().getType().isAir(), "the cursor is empty before the save");
        assertTrue(mira.getInventory().containsAtLeast(new ItemStack(Material.DIAMOND), 3), "the three diamonds are in her inventory");
    }

    // --- command -----------------------------------------------------------------------------------------------------------

    void statusAndReload() {
        drain(staff);
        staff.performCommand("dupesentry status");
        List<String> lines = drain(staff);
        assertTrue(lines.get(0).contains("NYR DupeSentry") && lines.get(0).contains("on"), String.join(" | ", lines));
        for (String guard : List.of("piston-dupes", "portal-gravity", "tripwire-hooks", "container-desync")) {
            assertTrue(lines.stream().anyMatch(line -> line.contains(guard)), guard + " is listed: " + String.join(" | ", lines));
        }
        assertTrue(statusLine("piston-dupes").contains("on (TNT, carpets and rails)"), statusLine("piston-dupes"));

        PlayerMock player = server.addPlayer("Plain");
        player.performCommand("dupesentry status");
        assertTrue(drain(player).stream().anyMatch(line -> line.contains("do not have permission")), "players cannot read the status");

        staff.performCommand("dsentry reload");
        assertTrue(drain(staff).stream().anyMatch(line -> line.contains("Reloaded config.yml")), "the alias reloads");
    }

    // --- helpers -----------------------------------------------------------------------------------------------------------

    private static List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = player.nextMessage()) != null) {
            lines.add(line.replaceAll("§.", ""));
        }
        return lines;
    }

    private String statusLine(String guard) {
        drain(staff);
        staff.performCommand("dupesentry status");
        return drain(staff).stream().filter(line -> line.contains(guard)).findFirst().orElse("(no line for " + guard + ")");
    }

    private long stopped(String guard) {
        String line = statusLine(guard);
        Matcher matcher = STOPPED.matcher(line);
        assertTrue(matcher.find(), "a count in: " + line);
        return Long.parseLong(matcher.group(1));
    }

    private File configFile() {
        return new File(plugin.getDataFolder(), "config.yml");
    }

    private void setGuardEnabled(String guard, boolean enabled) {
        setGuardSetting(guard, "enabled", !enabled, enabled);
    }

    /** Flips one boolean in a guard's section of config.yml, as a server owner would, and reloads. */
    private void setGuardSetting(String guard, String key, boolean from, boolean to) {
        try {
            File file = configFile();
            String text = Files.readString(file.toPath(), StandardCharsets.UTF_8).replace("\r\n", "\n");
            int section = text.indexOf("\n  " + guard + ":\n");
            assertNotNull(section >= 0 ? Boolean.TRUE : null, "config.yml has a " + guard + " section");
            String needle = "\n    " + key + ": " + from + "\n";
            int at = text.indexOf(needle, section);
            assertTrue(at > section, "the " + guard + " section has " + key + ": " + from);
            text = text.substring(0, at) + "\n    " + key + ": " + to + "\n" + text.substring(at + needle.length());
            Files.writeString(file.toPath(), text, StandardCharsets.UTF_8);
        } catch (java.io.IOException unreadable) {
            throw new AssertionError(unreadable);
        }
        server.dispatchCommand(server.getConsoleSender(), "dupesentry reload");
    }
}

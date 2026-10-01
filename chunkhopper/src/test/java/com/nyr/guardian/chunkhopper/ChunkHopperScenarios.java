package com.nyr.guardian.chunkhopper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Chunk;
import org.bukkit.ExplosionResult;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.TileState;
import org.bukkit.entity.Item;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.opentest4j.TestAbortedException;

/**
 * NYR ChunkHopper played through the Bukkit API only, so the same scenarios run on the compiled classes and on the built jar.
 * Items spawn the way a server spawns them (the entity exists, ItemSpawnEvent fires, a cancelled spawn removes it); a block
 * break fires BlockBreakEvent, then BlockDropItemEvent with the items not yet in the world, then their spawns.
 */
public final class ChunkHopperScenarios {

    static final NamespacedKey ITEM = new NamespacedKey("nyrchunkhopper", "item");
    static final NamespacedKey GHOST = new NamespacedKey("nyrchunkhopper", "ghost");
    static final NamespacedKey HOPPER = new NamespacedKey("nyrchunkhopper", "hopper");
    static final NamespacedKey POSITION = new NamespacedKey("nyrchunkhopper", "position");
    static final NamespacedKey ID = new NamespacedKey("nyrchunkhopper", "id");
    static final NamespacedKey SALE_SEQ = new NamespacedKey("nyrchunkhopper", "sale-seq");
    private static final int INTERVAL_TICKS = 10 * 20;
    private static final int MENU = 18;

    private final ServerMock server;
    private final Plugin plugin;
    private final WorldMock world;
    private final TestEconomy economy = new TestEconomy();
    private TestPlayer kai;
    private TestPlayer luna;
    private TestPlayer staff;
    /** Kai's Chunk Hopper, in chunk 0,0. */
    private Block hopper;

    public ChunkHopperScenarios(ServerMock server, Plugin plugin) {
        this.server = server;
        this.plugin = plugin;
        this.world = server.getWorlds().isEmpty() ? server.addSimpleWorld("world") : (WorldMock) server.getWorlds().get(0);
    }

    public void all() {
        try {
            setUp();
            giveAndPlace();
            onePerChunk();
            collects();
            leavesWhatPlayersGetOrLose();
            filterMenu();
            menuClicksNeverHandOutIcons();
            sells();
            refusedSalePutsTheItemsBack();
            crashAfterASaleNeverPaysTwice();
            saleInFlightWhenThePluginStopsIsPaidOnce();
            protectsAndBreaks();
            explosionsAndMobs();
            reloadKeepsState();
            chunkUnloadAndLoad();
            rolledBackHopperIsForgotten();
            perPlayerLimit();
            disabledWorld();
            commands();
        } catch (TestAbortedException unimplemented) {
            fail("MockBukkit could not run part of the scenario, so it proved nothing: " + unimplemented.getMessage(), unimplemented);
        }
    }

    // ------------------------------------------------------------------ steps

    void setUp() {
        Plugin host = MockBukkit.createMockPlugin("NyrTestEconomyHost");
        server.getServicesManager().register(Economy.class, economy, host, ServicePriority.Highest);
        kai = join("Kai");
        luna = join("Luna");
        staff = join("Staff");
        staff.setOp(true);
        for (int cx = -1; cx <= 3; cx++) {
            for (int cz = -1; cz <= 2; cz++) {
                world.loadChunk(cx, cz);
            }
        }
    }

    void giveAndPlace() {
        command(staff, "chunkhopper give Kai 2");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("Gave") && line.contains("2") && line.contains("Kai")));
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("You received") && line.contains("2")));
        ItemStack given = chunkHoppers(kai);
        assertNotNull(given, "Kai holds Chunk Hopper items");
        assertEquals(2, given.getAmount());
        assertTrue(given.getItemMeta().getPersistentDataContainer().has(ITEM, PersistentDataType.BYTE));

        hold(kai, given);
        hopper = world.getBlockAt(8, 64, 8);
        BlockPlaceEvent placed = place(kai, hopper);
        assertFalse(placed.isCancelled(), "Kai may place his first Chunk Hopper");
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("Chunk Hopper placed")));
        TileState state = (TileState) hopper.getState();
        assertTrue(state.getPersistentDataContainer().has(HOPPER, PersistentDataType.INTEGER), "the hopper's TileState is marked");
        assertEquals(kai.getUniqueId().toString(), state.getPersistentDataContainer().get(new NamespacedKey("nyrchunkhopper", "owner"), PersistentDataType.STRING));
        int[] pointer = hopper.getChunk().getPersistentDataContainer().get(POSITION, PersistentDataType.INTEGER_ARRAY);
        assertNotNull(pointer, "the chunk points at its Chunk Hopper");
        assertEquals(List.of(8, 64, 8), List.of(pointer[0], pointer[1], pointer[2]));
    }

    void onePerChunk() {
        Block second = world.getBlockAt(12, 64, 3);
        BlockPlaceEvent refused = place(kai, second);
        assertTrue(refused.isCancelled(), "a second Chunk Hopper in the same chunk is refused");
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("already has a Chunk Hopper") && line.contains("8, 64, 8")));
        assertEquals(Material.AIR, second.getType());
        assertEquals(1, chunkHoppers(kai).getAmount(), "the refused item stays with Kai");

        // A plain hopper in the same chunk is just a hopper.
        hold(kai, new ItemStack(Material.HOPPER));
        assertFalse(place(kai, world.getBlockAt(13, 64, 3)).isCancelled());
        assertEquals(Material.HOPPER, world.getBlockAt(13, 64, 3).getType());
        world.getBlockAt(13, 64, 3).setType(Material.AIR);
        kai.getInventory().setItemInMainHand(null);
    }

    void collects() {
        Inventory inside = inventory(hopper);
        inside.clear();
        Location drop = new Location(world, 3.5, 64.5, 12.5);
        Spawned cactus = spawn(drop, new ItemStack(Material.CACTUS, 5), 10, null);
        assertTrue(cactus.collected(), "a drop in the chunk never becomes an entity");
        assertEquals(5, count(inside, Material.CACTUS));

        // Partly fits: 10 of 20 dirt fit; the rest stays on the entity. Nothing here has a price, so nothing sells to make room.
        // (MockBukkit's hopper has 9 slots, a server's 5: the plugin asks the inventory.)
        inside.clear();
        int last = inside.getSize() - 1;
        for (int slot = 0; slot < last; slot++) {
            inside.setItem(slot, new ItemStack(Material.STONE, 64));
        }
        inside.setItem(last, new ItemStack(Material.DIRT, 54));
        Spawned part = spawn(drop, new ItemStack(Material.DIRT, 20), 10, null);
        assertFalse(part.collected());
        assertEquals(10, part.item().getItemStack().getAmount(), "what did not fit stays on the ground");
        assertEquals(64, count(inside, Material.DIRT));
        Spawned full = spawn(drop, new ItemStack(Material.DIRT, 5), 10, null);
        assertFalse(full.collected(), "a full hopper leaves the drop alone");
        assertEquals(5, full.item().getItemStack().getAmount());

        // Full, but with something it can sell: it sells first, then takes the drop. (A full hopper that just found nothing
        // to sell waits a second before it tries again, so a busy farm does not price every drop.)
        pause(1_100);
        inside.setItem(last, new ItemStack(Material.CACTUS, 64));
        long before = economy.cents(kai.getUniqueId());
        Spawned sold = spawn(drop, new ItemStack(Material.CACTUS, 10), 10, null);
        assertTrue(sold.collected(), "a full hopper sells what it holds to make room");
        assertEquals(10, count(inside, Material.CACTUS));
        // The money follows once the sale is on disk in the journal.
        awaitTicks(() -> economy.cents(kai.getUniqueId()) - before == 12_800, "64 cactus at 2.00 paid");

        Spawned elsewhere = spawn(new Location(world, 20.5, 64.5, 3.5), new ItemStack(Material.CACTUS, 3), 10, null);
        assertFalse(elsewhere.collected(), "a drop in another chunk is not this hopper's");
        inside.clear();
    }

    void leavesWhatPlayersGetOrLose() {
        Inventory inside = inventory(hopper);
        Location drop = new Location(world, 5.5, 64.5, 5.5);
        assertFalse(spawn(drop, new ItemStack(Material.BONE, 3), 40, kai.getUniqueId()).collected(), "an item Kai throws");
        // /give puts the item in the inventory, then spawns a copy at the player (pickup delay 40 at spawn, "never" after).
        assertFalse(spawn(drop, new ItemStack(Material.APPLE, 1), 40, null).collected(), "the copy /give spawns");

        // Death drops, as Spigot spawns them (pickup delay 10) right after the event.
        luna.teleport(new Location(world, 6.5, 64, 6.5));
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.DIAMOND, 3), new ItemStack(Material.IRON_INGOT, 5)));
        server.getPluginManager().callEvent(new PlayerDeathEvent(luna, null, drops, 0, "Luna died"));
        Location at = luna.getLocation();
        assertFalse(spawn(at, new ItemStack(Material.DIAMOND, 3), 10, null).collected(), "Luna's death drop");
        assertFalse(spawn(at, new ItemStack(Material.IRON_INGOT, 5), 10, null).collected(), "Luna's death drop");
        server.getScheduler().performOneTick();
        assertTrue(spawn(at, new ItemStack(Material.DIAMOND, 3), 10, null).collected(), "the same stack a tick later is an ordinary drop");
        assertEquals(3, count(inside, Material.DIAMOND));

        // A fishing catch flies to the player who caught it.
        DropMock fish = new DropMock(server, new ItemStack(Material.COD, 1));
        fish.setLocation(drop);
        server.getPluginManager().callEvent(new PlayerFishEvent(kai, fish, null, PlayerFishEvent.State.CAUGHT_FISH));
        assertFalse(spawn(fish).collected(), "Kai's fishing catch");

        // A block Kai breaks: BlockDropItemEvent lists the drops before they spawn.
        Block stone = world.getBlockAt(5, 64, 5);
        stone.setType(Material.STONE);
        BlockState stoneState = stone.getState();
        call(new BlockBreakEvent(stone, kai));
        stone.setType(Material.AIR);
        DropMock cobble = new DropMock(server, new ItemStack(Material.COBBLESTONE, 1));
        cobble.setLocation(new Location(world, 5.5, 64.4, 5.5));
        call(new BlockDropItemEvent(stone, stoneState, kai, new ArrayList<>(List.of(cobble))));
        assertFalse(spawn(cobble).collected(), "what Kai mines stays for Kai");
        // Where no BlockDropItemEvent names the item (Spigot drops a broken container's contents on its own), the block's
        // cell says it: anything spawning there in that tick is what the player broke.
        Block chest = world.getBlockAt(6, 64, 5);
        chest.setType(Material.STONE);
        call(new BlockBreakEvent(chest, kai));
        chest.setType(Material.AIR);
        assertFalse(spawn(new Location(world, 6.3, 64.2, 5.7), new ItemStack(Material.GOLD_INGOT, 2), 0, null).collected());
        server.getScheduler().performOneTick();
        assertTrue(spawn(new Location(world, 6.3, 64.2, 5.7), new ItemStack(Material.GOLD_INGOT, 2), 0, null).collected(),
            "a tick later the cell is ordinary ground again");
        inside.clear();
    }

    void filterMenu() {
        Inventory menu = openMenu(kai);
        assertEquals(MENU, menu.getSize());
        for (int slot = 0; slot < 9; slot++) {
            assertNull(menu.getItem(slot), "an empty filter shows nine free slots");
        }
        assertIcon(menu.getItem(9), Material.BOOK);
        assertIcon(menu.getItem(13), Material.LIME_DYE);
        assertIcon(menu.getItem(17), Material.BARRIER);

        // Click a filter slot with cactus on the cursor: the slot shows cactus, the cursor keeps its cactus.
        kai.setItemOnCursor(new ItemStack(Material.CACTUS, 7));
        InventoryClickEvent set = click(kai, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        assertTrue(set.isCancelled());
        assertIcon(menu.getItem(0), Material.CACTUS);
        assertEquals(7, kai.getItemOnCursor().getAmount(), "the cursor keeps its item");
        assertEquals(Material.CACTUS, kai.getItemOnCursor().getType());
        kai.setItemOnCursor(null);

        // Shift-click a bone stack in Kai's own inventory: bone joins the filter, the bones stay where they are.
        kai.getInventory().setItem(20, new ItemStack(Material.BONE, 12));
        MenuView view = (MenuView) kai.getOpenInventory();
        InventoryClickEvent shift = click(kai, view.rawOf(20), ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertTrue(shift.isCancelled());
        assertIcon(menu.getItem(1), Material.BONE);
        assertEquals(12, kai.getInventory().getItem(20).getAmount());
        assertTrue(chat(kai).isEmpty());

        // With cactus and bone listed, beef stays on the ground and cactus is collected.
        Location drop = new Location(world, 2.5, 64.5, 2.5);
        assertFalse(spawn(drop, new ItemStack(Material.BEEF, 2), 10, null).collected(), "beef is not in the filter");
        assertTrue(spawn(drop, new ItemStack(Material.CACTUS, 4), 10, null).collected());

        // Click the bone slot with an empty cursor: it is free again.
        click(kai, 1, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        assertNull(menu.getItem(1));
        // Clicking cactus into slot 5 moves it there.
        kai.setItemOnCursor(new ItemStack(Material.CACTUS, 1));
        click(kai, 5, ClickType.RIGHT, InventoryAction.PLACE_ONE);
        kai.setItemOnCursor(null);
        assertNull(menu.getItem(0));
        assertIcon(menu.getItem(5), Material.CACTUS);

        // The menu fills: nine materials, then a tenth is refused with a message.
        Material[] nine = {Material.STONE, Material.DIRT, Material.SAND, Material.GRAVEL, Material.OAK_LOG, Material.BONE, Material.STRING, Material.ARROW};
        for (int i = 0; i < nine.length; i++) {
            kai.getInventory().setItem(9 + i, new ItemStack(nine[i], 1));
            click(kai, view.rawOf(9 + i), ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        }
        for (int slot = 0; slot < 9; slot++) {
            assertNotNull(menu.getItem(slot), "slot " + slot + " filled");
        }
        kai.getInventory().setItem(30, new ItemStack(Material.EMERALD, 1));
        click(kai, view.rawOf(30), ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY);
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("All 9 filter slots")));

        // Clear: an empty filter collects everything again.
        click(kai, 17, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        for (int slot = 0; slot < 9; slot++) {
            assertNull(menu.getItem(slot));
        }
        assertTrue(spawn(drop, new ItemStack(Material.BEEF, 2), 10, null).collected());

        // Cactus only, for the rest of the scenario.
        kai.setItemOnCursor(new ItemStack(Material.CACTUS, 1));
        click(kai, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        kai.setItemOnCursor(null);
        kai.closeInventory();
        assertFalse(hasGhost(kai));
        inventory(hopper).clear();
        for (int slot = 9; slot < 36; slot++) {
            kai.getInventory().setItem(slot, null);
        }

        // Luna may not open it.
        luna.setSneaking(true);
        PlayerInteractEvent refused = interact(luna);
        assertEquals(Event.Result.DENY, refused.useInteractedBlock());
        assertTrue(chat(luna).stream().anyMatch(line -> line.contains("belongs to Kai")));
        assertFalse(luna.getOpenInventory() instanceof MenuView, "no menu opened for Luna");
        luna.setSneaking(false);
        // Without sneaking, the hopper opens as a hopper (the plugin stays out of it).
        PlayerInteractEvent plain = interact(kai);
        assertEquals(Event.Result.ALLOW, plain.useInteractedBlock());
    }

    void menuClicksNeverHandOutIcons() {
        Inventory menu = openMenu(kai);
        MenuView view = (MenuView) kai.getOpenInventory();
        kai.getInventory().setItem(0, new ItemStack(Material.CACTUS, 16));
        kai.getInventory().setItem(8, new ItemStack(Material.BONE, 5));
        int clicks = 0;
        for (int raw = 0; raw < MENU; raw++) {
            for (ClickType type : ClickType.values()) {
                for (InventoryAction action : InventoryAction.values()) {
                    for (int key : type == ClickType.NUMBER_KEY ? new int[] {0, 8} : new int[] {-1}) {
                        InventoryClickEvent event = key < 0 ? click(kai, raw, type, action) : clickKey(kai, raw, action, key);
                        assertTrue(event.isCancelled(), "a " + type + "/" + action + " click on menu slot " + raw + " was allowed");
                        clicks++;
                    }
                }
            }
            // The same clicks with an item on the cursor.
            kai.setItemOnCursor(new ItemStack(Material.CACTUS, 1));
            for (ClickType type : ClickType.values()) {
                assertTrue(click(kai, raw, type, InventoryAction.SWAP_WITH_CURSOR).isCancelled());
                clicks++;
            }
            kai.setItemOnCursor(null);
        }
        // The player's own inventory: only what could reach the menu is stopped.
        for (int raw = MENU; raw < MENU + 36; raw++) {
            for (ClickType type : ClickType.values()) {
                for (InventoryAction action : InventoryAction.values()) {
                    boolean reaches = action == InventoryAction.MOVE_TO_OTHER_INVENTORY || action == InventoryAction.COLLECT_TO_CURSOR
                        || action == InventoryAction.UNKNOWN || type == ClickType.DOUBLE_CLICK || type == ClickType.UNKNOWN;
                    InventoryClickEvent event = click(kai, raw, type, action);
                    assertEquals(reaches, event.isCancelled(), type + "/" + action + " at raw " + raw);
                    clicks++;
                }
            }
        }
        assertEquals(Event.Result.DENY, click(kai, InventoryView.OUTSIDE, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR).getResult());
        // Drags: any drag touching the menu is stopped; one inside Kai's inventory is his business.
        assertTrue(drag(kai, Map.of(0, new ItemStack(Material.CACTUS, 1), MENU + 1, new ItemStack(Material.CACTUS, 1))).isCancelled());
        assertTrue(drag(kai, Map.of(4, new ItemStack(Material.CACTUS, 1))).isCancelled());
        assertFalse(drag(kai, Map.of(MENU + 1, new ItemStack(Material.CACTUS, 1), MENU + 2, new ItemStack(Material.CACTUS, 1))).isCancelled());
        assertTrue(clicks > 10_000, "every click type and action was tried: " + clicks);

        assertFalse(hasGhost(kai), "no menu icon ever reached Kai");
        assertEquals(16, kai.getInventory().getItem(0).getAmount());
        assertIcon(menu.getItem(9), Material.BOOK);
        assertIcon(menu.getItem(17), Material.BARRIER);

        // The last line of defence: an icon found on the player when the menu closes is taken away and reported.
        ItemStack smuggled = menu.getItem(9).clone();
        kai.getInventory().setItem(3, smuggled);
        kai.closeInventory();
        assertNull(kai.getInventory().getItem(3), "an icon on the player is removed when the menu closes");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("Kai") && line.contains("filter icon")), "staff are told");

        // Leave the filter at cactus and auto-sell on for the steps that follow.
        setFilter(kai, Material.CACTUS);
        kai.getInventory().clear();
    }

    void sells() {
        // What the full hopper sold earlier is told at the next interval; take that message out of the way first.
        server.getScheduler().performTicks(INTERVAL_TICKS + 5);
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("+$128.00") && line.contains("64 items")), "the sale made to fit a drop is told too");
        Inventory inside = inventory(hopper);
        inside.clear();
        inside.setItem(0, new ItemStack(Material.CACTUS, 20));
        inside.setItem(1, new ItemStack(Material.BONE, 10));
        inside.setItem(2, new ItemStack(Material.STONE, 5));
        long before = economy.cents(kai.getUniqueId());
        String id = hopperId();
        long seqBefore = saleSeq();
        List<String> checkedAtDeposit = new ArrayList<>();
        economy.onDeposit(() -> {
            assertEquals(0, count(inventory(hopper), Material.CACTUS), "the items leave the hopper before the money is paid");
            assertEquals(0, count(inventory(hopper), Material.BONE));
            assertEquals(seqBefore + 1, saleSeq(), "the hopper's sale number rose with the removal");
            assertTrue(journalText().contains("S " + id + " " + (seqBefore + 1) + " "), "the sale is on disk before the money is paid");
            checkedAtDeposit.add("paid");
        });
        chat(kai);
        server.getScheduler().performTicks(INTERVAL_TICKS + 5);
        awaitTicks(() -> economy.cents(kai.getUniqueId()) - before == 5_000, "20 cactus at 2.00 and 10 bones at 1.00 paid");
        economy.onDeposit(null);
        assertEquals(List.of("paid"), checkedAtDeposit, "one deposit");
        assertEquals(0, count(inside, Material.CACTUS));
        assertEquals(0, count(inside, Material.BONE));
        assertEquals(5, count(inside, Material.STONE), "stone has no price and stays");
        server.getScheduler().performTicks(INTERVAL_TICKS + 5);
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("+$50.00") && line.contains("30 items")), "Kai is told once");

        // Nothing to sell: no deposit at all.
        long deposits = economy.deposits();
        server.getScheduler().performTicks(INTERVAL_TICKS + 5);
        assertEquals(deposits, economy.deposits());
        assertTrue(chat(kai).isEmpty());
        inside.clear();
    }

    void refusedSalePutsTheItemsBack() {
        Inventory inside = inventory(hopper);
        inside.clear();
        inside.setItem(3, new ItemStack(Material.CACTUS, 7));
        long before = economy.cents(kai.getUniqueId());
        economy.refuse(true);
        chat(staff);
        server.getScheduler().performTicks(INTERVAL_TICKS + 2);
        awaitTicks(() -> economy.refused() >= 1 && count(inside, Material.CACTUS) == 7, "the refused sale's cactus back");
        economy.refuse(false);
        assertEquals(before, economy.cents(kai.getUniqueId()), "no money for a refused sale");
        assertEquals(7, inside.getItem(3) == null ? 0 : inside.getItem(3).getAmount(), "the cactus is back in its own slot");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("refused to pay") && line.contains("items went back")));
        server.getScheduler().performTicks(INTERVAL_TICKS + 2);
        awaitTicks(() -> economy.cents(kai.getUniqueId()) - before == 1_400, "the next sale pays it");
        assertEquals(0, count(inside, Material.CACTUS));
    }

    /**
     * A crash after a sale was paid, before the world saved the sale: the world comes back with the stacks still in the
     * hopper and the hopper's older sale number. As its chunk loads, the recorded sale is found above that number and its
     * stacks are taken out again, before the hopper can pass them on; staff are told. A clean unload and load changes
     * nothing, and no sale is ever paid twice.
     */
    void crashAfterASaleNeverPaysTwice() {
        Inventory inside = inventory(hopper);
        inside.clear();
        inside.setItem(0, new ItemStack(Material.CACTUS, 10));
        inside.setItem(1, new ItemStack(Material.DIRT, 3));
        long before = economy.cents(kai.getUniqueId());
        long seqSaved = saleSeq();
        server.getScheduler().performTicks(INTERVAL_TICKS + 5);
        awaitTicks(() -> economy.cents(kai.getUniqueId()) - before == 2_000, "10 cactus sold and paid");
        assertEquals(0, count(inside, Material.CACTUS));
        long deposits = economy.deposits();

        // The crash: the chunk goes, and the world that loads again is the one saved before the sale.
        Chunk chunk = hopper.getChunk();
        call(new ChunkUnloadEvent(chunk));
        inside.addItem(new ItemStack(Material.CACTUS, 10));
        setSaleSeq(seqSaved);
        chat(staff);
        call(new ChunkLoadEvent(chunk, false));
        assertEquals(0, count(inside, Material.CACTUS), "the paid-for cactus the rolled-back world still had is taken out again at once");
        assertEquals(3, count(inside, Material.DIRT), "what was not sold stays");
        assertEquals(seqSaved + 1, saleSeq(), "the hopper's sale number catches up");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("came back from a crash") && line.contains("10 item(s) found again")),
            "staff are told what was taken out again");
        server.getScheduler().performTicks(INTERVAL_TICKS + 5);
        assertEquals(deposits, economy.deposits(), "nothing is paid again");
        assertEquals(2_000, economy.cents(kai.getUniqueId()) - before, "10 cactus, paid once");

        // A clean unload and load: the saved world has the sale; nothing is taken out, nothing is paid twice.
        inside.setItem(0, new ItemStack(Material.CACTUS, 4));
        server.getScheduler().performTicks(INTERVAL_TICKS + 5);
        awaitTicks(() -> economy.cents(kai.getUniqueId()) - before == 2_800, "4 more cactus paid");
        inside.setItem(2, new ItemStack(Material.STONE, 2));
        call(new ChunkUnloadEvent(chunk));
        call(new ChunkLoadEvent(chunk, false));
        assertEquals(2, count(inside, Material.STONE), "a clean reload takes nothing out");
        assertEquals(3, count(inside, Material.DIRT));
        server.getScheduler().performTicks(INTERVAL_TICKS + 5);
        assertEquals(2_800, economy.cents(kai.getUniqueId()) - before, "every item paid for once");
        command(staff, "chunkhopper status");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("crash rollbacks undone: 1 sale(s), 10 item(s)")));
        inside.clear();
    }

    /**
     * A sale whose stacks left the hopper and whose payment is still scheduled when the plugin stops (a reload, a
     * shutdown) is paid while stopping, and only then: the scheduled payment would go with the plugin's tasks.
     */
    void saleInFlightWhenThePluginStopsIsPaidOnce() {
        Inventory inside = inventory(hopper);
        inside.clear();
        inside.setItem(0, new ItemStack(Material.CACTUS, 5));
        long before = economy.cents(kai.getUniqueId());
        long deposits = economy.deposits();
        for (int tick = 0; tick < INTERVAL_TICKS + 5 && count(inside, Material.CACTUS) > 0; tick++) {
            server.getScheduler().performOneTick();
        }
        assertEquals(0, count(inside, Material.CACTUS), "the sale took the cactus out");
        command(staff, "chunkhopper reload");
        chat(staff);
        assertEquals(1_000, economy.cents(kai.getUniqueId()) - before, "the recorded sale is paid as the plugin stops");
        server.getScheduler().performTicks(INTERVAL_TICKS + 5);
        assertEquals(deposits + 1, economy.deposits(), "and paid once");
        assertEquals(1_000, economy.cents(kai.getUniqueId()) - before);
        chat(kai);
    }

    void protectsAndBreaks() {
        inventory(hopper).setItem(0, new ItemStack(Material.CACTUS, 5));
        inventory(hopper).setItem(1, new ItemStack(Material.HOPPER, 1));
        BlockBreakEvent byLuna = call(new BlockBreakEvent(hopper, luna));
        assertTrue(byLuna.isCancelled(), "Luna cannot break Kai's Chunk Hopper");
        assertTrue(chat(luna).stream().anyMatch(line -> line.contains("belongs to Kai")));
        kai.getInventory().setItemInMainHand(null);
        BlockBreakEvent byHand = call(new BlockBreakEvent(hopper, kai));
        assertTrue(byHand.isCancelled(), "by hand it would drop nothing, so it does not break");
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("pickaxe")));

        kai.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND_PICKAXE));
        BlockState before = hopper.getState();
        Chunk chunk = hopper.getChunk();
        BlockBreakEvent broken = call(new BlockBreakEvent(hopper, kai));
        assertFalse(broken.isCancelled());
        assertNull(chunk.getPersistentDataContainer().get(POSITION, PersistentDataType.INTEGER_ARRAY), "the chunk forgets it");
        hopper.setType(Material.AIR);
        // Paper lists the contents first and the block's own drop last; Spigot lists only the block's own drop.
        DropMock contentsCactus = drop(new ItemStack(Material.CACTUS, 5));
        DropMock contentsHopper = drop(new ItemStack(Material.HOPPER, 1));
        DropMock blockDrop = drop(new ItemStack(Material.HOPPER, 1));
        call(new BlockDropItemEvent(hopper, before, kai, new ArrayList<>(List.of(contentsCactus, contentsHopper, blockDrop))));
        assertTrue(isChunkHopper(blockDrop.getItemStack()), "the block drops a Chunk Hopper item");
        assertFalse(isChunkHopper(contentsHopper.getItemStack()), "a plain hopper from the contents stays plain");
        assertEquals(5, contentsCactus.getItemStack().getAmount(), "the contents drop once, as they were");
        for (DropMock item : List.of(contentsCactus, contentsHopper, blockDrop)) {
            assertFalse(spawn(item).collected(), "no Chunk Hopper is left here to take its own drops");
        }

        // Placed again, it is a Chunk Hopper again (with a fresh filter).
        hold(kai, blockDrop.getItemStack());
        assertFalse(place(kai, hopper).isCancelled());
        assertTrue(((TileState) hopper.getState()).getPersistentDataContainer().has(HOPPER, PersistentDataType.INTEGER));
        assertTrue(spawn(new Location(world, 4.5, 64.5, 4.5), new ItemStack(Material.BEEF, 1), 10, null).collected(), "a fresh filter takes anything");
        inventory(hopper).clear();
        setFilter(kai, Material.CACTUS);

        // Staff in creative break one without getting an item: vanilla drops nothing in creative.
        Block spare = world.getBlockAt(40, 64, 8);
        command(staff, "chunkhopper give Staff 1");
        staff.setGameMode(GameMode.CREATIVE);
        hold(staff, itemFrom(staff, 1));
        assertFalse(place(staff, spare).isCancelled());
        BlockState spareState = spare.getState();
        assertFalse(call(new BlockBreakEvent(spare, staff)).isCancelled());
        spare.setType(Material.AIR);
        DropMock nothingSpecial = drop(new ItemStack(Material.HOPPER, 1));
        call(new BlockDropItemEvent(spare, spareState, staff, new ArrayList<>(List.of(nothingSpecial))));
        assertFalse(isChunkHopper(nothingSpecial.getItemStack()), "creative breaks never mint a Chunk Hopper");
        staff.setGameMode(GameMode.SURVIVAL);
        chat(staff);
        chat(kai);
    }

    void explosionsAndMobs() {
        Block stone = world.getBlockAt(9, 64, 8);
        stone.setType(Material.STONE);
        List<Block> blocks = new ArrayList<>(List.of(hopper, stone));
        call(new EntityExplodeEvent(null, hopper.getLocation(), blocks, 1f, ExplosionResult.DESTROY));
        assertEquals(List.of(stone), blocks, "the explosion spares the Chunk Hopper and breaks the stone");
        List<Block> bed = new ArrayList<>(List.of(stone, hopper));
        call(new BlockExplodeEvent(stone, stone.getState(), bed, 1f, ExplosionResult.DESTROY));
        assertEquals(List.of(stone), bed);
        Zombie zombie = world.spawn(new Location(world, 8.5, 65, 8.5), Zombie.class);
        EntityChangeBlockEvent mob = call(new EntityChangeBlockEvent(zombie, hopper, Material.AIR.createBlockData()));
        assertTrue(mob.isCancelled(), "a mob cannot break it either");
        zombie.remove();
        stone.setType(Material.AIR);
    }

    void reloadKeepsState() {
        Location drop = new Location(world, 7.5, 64.5, 1.5);
        assertTrue(spawn(drop, new ItemStack(Material.CACTUS, 6), 10, null).collected());
        long collected = infoCollected();
        // A menu open while the plugin stops is emptied and closed first: without the plugin's listeners its icons
        // could otherwise be taken.
        Inventory open = openMenu(kai);
        assertNotNull(open.getItem(0));
        command(staff, "chunkhopper reload");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("Reloaded")));
        for (ItemStack left : open.getContents()) {
            assertNull(left, "the open menu was emptied when the plugin stopped");
        }
        assertFalse(kai.getOpenInventory() instanceof MenuView, "and closed");
        assertFalse(hasGhost(kai));
        server.getScheduler().performOneTick();
        assertFalse(spawn(drop, new ItemStack(Material.BONE, 1), 10, null).collected(), "the filter survived the reload");
        assertTrue(spawn(drop, new ItemStack(Material.CACTUS, 2), 10, null).collected(), "the hopper collects after the reload");
        assertEquals(collected + 2, infoCollected(), "the counters survived the reload");
        assertEquals(8, count(inventory(hopper), Material.CACTUS), "the contents survived the reload");
        inventory(hopper).clear();
    }

    void chunkUnloadAndLoad() {
        Chunk chunk = hopper.getChunk();
        Location drop = new Location(world, 1.5, 64.5, 14.5);
        assertTrue(spawn(drop, new ItemStack(Material.CACTUS, 1), 10, null).collected());
        long collected = infoCollected();
        call(new ChunkUnloadEvent(chunk));
        assertFalse(spawn(drop, new ItemStack(Material.CACTUS, 1), 10, null).collected(), "an unloaded chunk's hopper is put away");
        call(new ChunkLoadEvent(chunk, false));
        server.getScheduler().performOneTick();
        assertTrue(spawn(drop, new ItemStack(Material.CACTUS, 1), 10, null).collected(), "found again when its chunk loads");
        assertEquals(collected + 1, infoCollected(), "the counters were written when the chunk unloaded");
        inventory(hopper).clear();
    }

    /**
     * A crash rolls the world back past a placement: the block, its TileState and the chunk's pointer are gone, but
     * hoppers.yml (written at once, off the world) still lists it. When the chunk loads again the list follows the world.
     */
    void rolledBackHopperIsForgotten() {
        command(staff, "chunkhopper give Kai 1");
        chat(kai);
        chat(staff);
        Block lost = world.getBlockAt(56, 64, 20);
        hold(kai, itemFrom(kai, 1));
        assertFalse(place(kai, lost).isCancelled());
        command(staff, "chunkhopper list Kai");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("56 64 20")), "listed after placing");
        Chunk chunk = lost.getChunk();
        call(new ChunkUnloadEvent(chunk));
        // The world as it was saved before the placement.
        lost.setType(Material.AIR);
        chunk.getPersistentDataContainer().remove(POSITION);
        call(new ChunkLoadEvent(chunk, false));
        server.getScheduler().performOneTick();
        command(staff, "chunkhopper list Kai");
        List<String> list = chat(staff);
        assertFalse(list.stream().anyMatch(line -> line.contains("56 64 20")), "a rolled-back Chunk Hopper leaves the list: " + list);
        assertTrue(list.stream().anyMatch(line -> line.contains("8 64 8")), list.toString());

        // A chunk that points at a block which is no longer a Chunk Hopper (removed while the plugin was off) is cleaned.
        Chunk stale = world.getBlockAt(70, 64, 70).getChunk();
        stale.getPersistentDataContainer().set(POSITION, PersistentDataType.INTEGER_ARRAY, new int[] {70, 64, 70});
        call(new ChunkLoadEvent(stale, false));
        server.getScheduler().performOneTick();
        assertNull(stale.getPersistentDataContainer().get(POSITION, PersistentDataType.INTEGER_ARRAY), "the stale pointer is removed");
    }

    void disabledWorld() {
        editConfig(text -> text.replace("  disabled: []", "  disabled:\n    - " + world.getName()));
        command(staff, "chunkhopper reload");
        server.getScheduler().performOneTick();
        chat(staff);
        command(staff, "chunkhopper give Kai 1");
        chat(kai);
        hold(kai, itemFrom(kai, 1));
        Block elsewhere = world.getBlockAt(100, 64, 100);
        assertTrue(place(kai, elsewhere).isCancelled(), "no Chunk Hopper is placed in a disabled world");
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("do not work in this world")));
        assertFalse(spawn(new Location(world, 8.5, 65, 9.5), new ItemStack(Material.CACTUS, 1), 10, null).collected(),
            "in a disabled world a Chunk Hopper is a plain hopper");
        editConfig(text -> text.replace("  disabled:\n    - " + world.getName(), "  disabled: []"));
        command(staff, "chunkhopper reload");
        server.getScheduler().performOneTick();
        chat(staff);
        assertTrue(spawn(new Location(world, 8.5, 65, 9.5), new ItemStack(Material.CACTUS, 1), 10, null).collected(), "and collects again when enabled");
        inventory(hopper).clear();
    }

    void perPlayerLimit() {
        editConfig(text -> text.replace("per-player: 0", "per-player: 1"));
        command(staff, "chunkhopper reload");
        server.getScheduler().performOneTick();
        chat(staff);
        command(staff, "chunkhopper give Kai 2");
        chat(kai);
        Block third = world.getBlockAt(24, 64, 24);
        hold(kai, itemFrom(kai, 2));
        assertTrue(place(kai, third).isCancelled(), "Kai owns one, his limit");
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("your limit")));
        PermissionAttachment more = kai.addAttachment(plugin, "nyrchunkhopper.limit.2", true);
        assertFalse(place(kai, third).isCancelled(), "nyrchunkhopper.limit.2 allows a second one");
        kai.removeAttachment(more);
        editConfig(text -> text.replace("per-player: 1", "per-player: 0"));
        command(staff, "chunkhopper reload");
        server.getScheduler().performOneTick();
        chat(staff);
        chat(kai);
    }

    void commands() {
        command(kai, "chunkhopper list");
        List<String> list = chat(kai);
        assertTrue(list.stream().anyMatch(line -> line.contains("Kai") && line.contains("owns") && line.contains("2")), list.toString());
        assertTrue(list.stream().anyMatch(line -> line.contains("8 64 8")), list.toString());
        assertTrue(list.stream().anyMatch(line -> line.contains("24 64 24")), list.toString());
        command(luna, "chunkhopper list Kai");
        assertTrue(chat(luna).stream().anyMatch(line -> line.contains("permission")));
        command(staff, "chunkhopper list Kai");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("8 64 8")));
        command(kai, "chunkhopper status");
        assertTrue(chat(kai).stream().anyMatch(line -> line.contains("permission")), "status is for staff");
        command(staff, "chunkhopper status");
        List<String> status = chat(staff);
        assertTrue(status.stream().anyMatch(line -> line.contains("NYR ChunkHopper")), status.toString());
        assertTrue(status.stream().anyMatch(line -> line.contains("Collected since start")), status.toString());
        assertTrue(status.stream().anyMatch(line -> line.contains("TestEconomy")), status.toString());
        luna.lookAt(hopper);
        command(luna, "chunkhopper info");
        assertTrue(chat(luna).stream().anyMatch(line -> line.contains("belongs to Kai")));
        command(staff, "chunkhopper give Nobody");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("No online player")));
        command(staff, "chunkhopper give Kai 0");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("Usage")));
        command(staff, "chunkhopper");
        assertTrue(chat(staff).stream().anyMatch(line -> line.contains("give")));

        org.bukkit.inventory.Recipe recipe = server.getRecipe(new NamespacedKey("nyrchunkhopper", "chunk_hopper"));
        assertTrue(recipe instanceof org.bukkit.inventory.ShapedRecipe, "the recipe is registered");
        org.bukkit.inventory.ShapedRecipe shaped = (org.bukkit.inventory.ShapedRecipe) recipe;
        assertTrue(isChunkHopper(shaped.getResult()), "and makes a Chunk Hopper item");
        assertEquals(List.of("IHI", "HEH", "IHI"), List.of(shaped.getShape()));
        assertEquals(Material.ENDER_PEARL, shaped.getIngredientMap().get('E').getType());
    }

    // ------------------------------------------------------------------ helpers

    private TestPlayer join(String name) {
        TestPlayer player = new TestPlayer(server, name);
        server.addPlayer(player);
        player.teleport(spot(8, 12));
        return player;
    }

    private Location spot(int x, int z) {
        return new Location(world, x + 0.5, 64, z + 0.5);
    }

    static List<String> chat(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            lines.add(line.replaceAll("§.", ""));
        }
        return lines;
    }

    private void command(PlayerMock player, String line) {
        assertTrue(player.performCommand(line), "/" + line + " was not handled");
    }

    private <T extends Event> T call(T event) {
        server.getPluginManager().callEvent(event);
        return event;
    }

    private static Inventory inventory(Block block) {
        return ((Container) block.getState()).getInventory();
    }

    private static int count(Inventory inventory, Material material) {
        int count = 0;
        for (ItemStack stack : inventory.getContents()) {
            if (stack != null && stack.getType() == material) {
                count += stack.getAmount();
            }
        }
        return count;
    }

    private static boolean isChunkHopper(ItemStack stack) {
        return stack != null && stack.getType() == Material.HOPPER && stack.hasItemMeta()
            && stack.getItemMeta().getPersistentDataContainer().has(ITEM, PersistentDataType.BYTE);
    }

    /** The first stack of Chunk Hopper items the player carries, or null. */
    private static ItemStack chunkHoppers(PlayerMock player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isChunkHopper(stack)) {
                return stack;
            }
        }
        return null;
    }

    /** {@code amount} of the player's Chunk Hopper items, taken out of their inventory. */
    private static ItemStack itemFrom(PlayerMock player, int amount) {
        ItemStack stack = chunkHoppers(player);
        assertNotNull(stack, player.getName() + " has no Chunk Hopper item");
        ItemStack taken = stack.clone();
        taken.setAmount(amount);
        player.getInventory().removeItem(taken);
        return taken;
    }

    private static void hold(PlayerMock player, ItemStack stack) {
        player.getInventory().removeItem(stack.clone());
        player.getInventory().setItemInMainHand(stack);
    }

    /** Places the held item on {@code block} the way a server does: the block changes, the event fires, a refusal reverts it. */
    private BlockPlaceEvent place(PlayerMock player, Block block) {
        BlockState before = block.getState();
        ItemStack hand = player.getInventory().getItemInMainHand();
        block.setType(Material.HOPPER);
        BlockPlaceEvent event = call(new BlockPlaceEvent(block, before, block.getRelative(BlockFace.DOWN), hand.clone(), player, true, EquipmentSlot.HAND));
        if (event.isCancelled()) {
            block.setType(before.getType());
        } else {
            hand.setAmount(hand.getAmount() - 1);
            player.getInventory().setItemInMainHand(hand.getAmount() > 0 ? hand : null);
        }
        return event;
    }

    private record Spawned(Item item, boolean collected) {
    }

    /** Spawns an item as the server does: it exists, ItemSpawnEvent fires, and a cancelled spawn removes it. */
    private Spawned spawn(Location at, ItemStack stack, int pickupDelay, UUID thrower) {
        DropMock item = new DropMock(server, stack);
        item.setLocation(at);
        item.setPickupDelay(pickupDelay);
        item.setThrower(thrower);
        return spawn(item);
    }

    private Spawned spawn(DropMock item) {
        server.registerEntity(item);
        ItemSpawnEvent event = call(new ItemSpawnEvent(item));
        if (event.isCancelled()) {
            item.remove();
        }
        return new Spawned(item, event.isCancelled());
    }

    /** An item a block break is about to drop at the hopper: made, not yet in the world. */
    private DropMock drop(ItemStack stack) {
        DropMock item = new DropMock(server, stack);
        item.setLocation(new Location(world, hopper.getX() + 0.5, hopper.getY() + 0.3, hopper.getZ() + 0.5));
        item.setPickupDelay(10);
        return item;
    }

    private PlayerInteractEvent interact(PlayerMock player) {
        return call(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, hopper, BlockFace.UP, EquipmentSlot.HAND));
    }

    /** Shift + right-click with an empty hand opens the menu; returns its top inventory. */
    private Inventory openMenu(TestPlayer player) {
        player.getInventory().setItemInMainHand(null);
        player.setSneaking(true);
        PlayerInteractEvent event = interact(player);
        player.setSneaking(false);
        assertEquals(Event.Result.DENY, event.useInteractedBlock(), "the plugin answers the click instead of the hopper");
        InventoryView view = player.getOpenInventory();
        assertTrue(view instanceof MenuView, "the filter menu opened");
        assertEquals(MENU, view.getTopInventory().getSize());
        return view.getTopInventory();
    }

    private InventoryClickEvent click(TestPlayer player, int raw, ClickType type, InventoryAction action) {
        InventoryView view = player.getOpenInventory();
        return call(new InventoryClickEvent(view, view.getSlotType(raw), raw, type, action));
    }

    private InventoryClickEvent clickKey(TestPlayer player, int raw, InventoryAction action, int key) {
        InventoryView view = player.getOpenInventory();
        return call(new InventoryClickEvent(view, view.getSlotType(raw), raw, ClickType.NUMBER_KEY, action, key));
    }

    private InventoryDragEvent drag(TestPlayer player, Map<Integer, ItemStack> slots) {
        InventoryView view = player.getOpenInventory();
        return call(new InventoryDragEvent(view, null, new ItemStack(Material.CACTUS, slots.size()), false, new HashMap<>(slots)));
    }

    /** Sets the hopper's filter to exactly this one material through the menu. */
    private void setFilter(TestPlayer owner, Material material) {
        Inventory menu = openMenu(owner);
        click(owner, 17, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        owner.setItemOnCursor(new ItemStack(material, 1));
        click(owner, 0, ClickType.LEFT, InventoryAction.PLACE_ALL);
        owner.setItemOnCursor(null);
        assertIcon(menu.getItem(0), material);
        owner.closeInventory();
    }

    private static void assertIcon(ItemStack icon, Material material) {
        assertNotNull(icon, "an icon of " + material);
        assertEquals(material, icon.getType());
        ItemMeta meta = icon.getItemMeta();
        assertTrue(meta.getPersistentDataContainer().has(GHOST, PersistentDataType.BYTE), "every menu icon carries the ghost key");
    }

    private static boolean hasGhost(PlayerMock player) {
        List<ItemStack> all = new ArrayList<>(java.util.Arrays.asList(player.getInventory().getContents()));
        all.add(player.getItemOnCursor());
        for (ItemStack stack : all) {
            if (stack != null && stack.hasItemMeta() && stack.getItemMeta().getPersistentDataContainer().has(GHOST, PersistentDataType.BYTE)) {
                return true;
            }
        }
        return false;
    }

    /** The collected counter of Kai's hopper, read from /chunkhopper info. */
    private long infoCollected() {
        kai.lookAt(hopper);
        command(kai, "chunkhopper info");
        List<String> lines = chat(kai);
        for (String line : lines) {
            int at = line.indexOf("Collected: ");
            if (at >= 0) {
                String number = line.substring(at + "Collected: ".length()).split(" ")[0];
                return Long.parseLong(number);
            }
        }
        fail("/chunkhopper info shows no counters: " + lines);
        return -1;
    }

    /** Ticks the server until {@code done}: payments follow a sale once its journal record is on disk (a moment later). */
    private void awaitTicks(java.util.function.BooleanSupplier done, String what) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!done.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("timed out waiting for " + what);
            }
            server.getScheduler().performOneTick();
            pause(5);
        }
    }

    private String hopperId() {
        return ((TileState) hopper.getState()).getPersistentDataContainer().get(ID, PersistentDataType.STRING);
    }

    private long saleSeq() {
        Long seq = ((TileState) hopper.getState()).getPersistentDataContainer().get(SALE_SEQ, PersistentDataType.LONG);
        return seq == null ? 0 : seq;
    }

    /** What the world saved before the last sale: the hopper's older sale number (the stacks are put back by the caller). */
    private void setSaleSeq(long seq) {
        TileState state = (TileState) hopper.getState();
        state.getPersistentDataContainer().set(SALE_SEQ, PersistentDataType.LONG, seq);
        state.update(true, false);
    }

    private String journalText() {
        try {
            return Files.readString(new File(plugin.getDataFolder(), "sales.journal").toPath(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new AssertionError(unreadable);
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private void editConfig(UnaryOperator<String> edit) {
        File file = new File(plugin.getDataFolder(), "config.yml");
        try {
            String before = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            String after = edit.apply(before);
            assertFalse(before.equals(after), "the config edit changed nothing");
            Files.writeString(file.toPath(), after, StandardCharsets.UTF_8);
        } catch (IOException broken) {
            throw new AssertionError(broken);
        }
    }
}

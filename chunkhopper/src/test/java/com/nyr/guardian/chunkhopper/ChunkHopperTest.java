package com.nyr.guardian.chunkhopper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChunkHopperTest {

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
    void scenariosOnTheCompiledClasses() {
        ChunkHopperPlugin plugin = MockBukkit.load(ChunkHopperPlugin.class);
        new ChunkHopperScenarios(server, plugin).all();
    }

    @Test
    void filterSlotsMoveFillAndSurviveTheirStoredForm() {
        Filter filter = Filter.EMPTY;
        assertTrue(filter.accepts(Material.DIRT), "an empty filter takes everything");
        filter = filter.with(3, Material.CACTUS);
        assertEquals(Material.CACTUS, filter.slot(3));
        assertTrue(filter.accepts(Material.CACTUS));
        assertFalse(filter.accepts(Material.DIRT));
        Filter moved = filter.with(7, Material.CACTUS);
        assertNull(moved.slot(3), "setting a listed material elsewhere moves it");
        assertEquals(Material.CACTUS, moved.slot(7));
        assertEquals(1, moved.size());
        assertSame(moved, moved.adding(Material.CACTUS), "adding what is listed changes nothing");
        Filter full = Filter.EMPTY;
        Material[] nine = {Material.STONE, Material.DIRT, Material.SAND, Material.GRAVEL, Material.OAK_LOG, Material.BONE,
            Material.STRING, Material.ARROW, Material.CACTUS};
        for (Material material : nine) {
            full = full.adding(material);
            assertNotNull(full);
        }
        assertNull(full.adding(Material.EMERALD), "a tenth material does not fit");
        assertEquals(full, Filter.parse(full.serialize()), "the stored form reads back the same, slot for slot");
        assertEquals(",,,,,,,minecraft:cactus,", moved.serialize());
        assertEquals(Filter.EMPTY, Filter.parse(""));
        Filter unknown = Filter.parse("minecraft:no_such_item,minecraft:bone");
        assertNull(unknown.slot(0), "an item this server does not know leaves its slot free");
        assertEquals(Material.BONE, unknown.slot(1));
        assertEquals("cactus", moved.describe("everything"));
        assertEquals("everything", Filter.EMPTY.describe("everything"));
        assertSame(moved, moved.without(0));
        assertEquals(Filter.EMPTY, moved.without(7));
    }

    @Test
    void offeringAStackReturnsWhatDidNotFit() {
        // MockBukkit's hopper inventory has 9 slots (a server's has 5); the plugin only ever asks the inventory its size.
        Inventory hopper = server.createInventory(null, InventoryType.HOPPER);
        int last = hopper.getSize() - 1;
        for (int slot = 0; slot < last; slot++) {
            hopper.setItem(slot, new ItemStack(Material.STONE, 64));
        }
        hopper.setItem(last, new ItemStack(Material.DIRT, 60));
        assertNull(Collector.offer(hopper, new ItemStack(Material.DIRT, 4)), "four fit");
        ItemStack rest = Collector.offer(hopper, new ItemStack(Material.DIRT, 9));
        assertNotNull(rest);
        assertEquals(9, rest.getAmount(), "none fit");
        assertEquals(Material.DIRT, rest.getType());
        hopper.setItem(last, new ItemStack(Material.DIRT, 50));
        assertEquals(6, Collector.offer(hopper, new ItemStack(Material.DIRT, 20)).getAmount(), "fourteen of twenty fit");
    }

    @Test
    void pricesComeFromConfigAndSkipUnknownIdsAndFullContainers() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.set("prices.cactus", 2.5);
        config.set("prices.bone", 0);
        config.set("prices.no_such_item", 3);
        config.set("prices.shulker_box", 100);
        Prices prices = Prices.from(config.getConfigurationSection("prices"), Prices.Source.AUTO, null);
        assertEquals(new BigDecimal("2.5"), prices.unit(new ItemStack(Material.CACTUS, 10)), "the price of one item");
        assertNull(prices.unit(new ItemStack(Material.BONE)), "a price of 0 means not for sale");
        assertNull(prices.unit(new ItemStack(Material.DIRT)), "no price, not for sale");
        assertEquals(List.of("no_such_item"), prices.unknown());
        assertEquals(new BigDecimal("100.0"), prices.unit(new ItemStack(Material.SHULKER_BOX)), "an empty shulker box sells");
        assertEquals(Prices.Source.PRICES_ONLY, Prices.Source.parse("prices-only"));
        assertEquals(Prices.Source.AUTO, Prices.Source.parse("something else"));
    }

    @Test
    void registryListsOwnersAndSurvivesARestart(@TempDir Path folder) throws Exception {
        File file = folder.resolve("hoppers.yml").toFile();
        Logger logger = Logger.getLogger("registry-test");
        Registry registry = new Registry(file, logger);
        registry.load();
        UUID world = UUID.randomUUID();
        UUID kai = UUID.randomUUID();
        UUID luna = UUID.randomUUID();
        registry.put(new Registry.Entry(world, "world", 8, 64, 8, kai, "Kai"));
        registry.put(new Registry.Entry(world, "world", -20, 70, 40, kai, "Kai"));
        registry.put(new Registry.Entry(world, "world", 100, 64, 100, luna, "Luna"));
        assertEquals(2, registry.count(kai));
        assertEquals(kai, registry.ownerNamed("kai"));
        registry.remove(new ChunkId(world, 6, 6), 100, 64, 101);
        assertEquals(1, registry.count(luna), "removing needs the same position");
        registry.remove(new ChunkId(world, 6, 6), 100, 64, 100);
        assertEquals(0, registry.count(luna));
        registry.close();
        assertTrue(Files.readString(file.toPath()).contains("owner-name: Kai"));

        Registry again = new Registry(file, logger);
        again.load();
        assertEquals(2, again.count(kai), "hoppers.yml reads back after a restart");
        assertEquals(List.of(-20, 8), again.owned(kai).stream().map(Registry.Entry::x).toList());
        assertNull(again.ownerNamed("Luna"));
        again.close();

        Files.writeString(file.toPath(), "hoppers: [ this is: not: yaml");
        Registry broken = new Registry(file, logger);
        broken.load();
        assertEquals(0, broken.size(), "a broken file leaves the index empty; chunks refill it as they load");
        broken.close();
    }

    @Test
    void saleJournalKeepsWhatIsNotYetSavedAcrossARestart(@TempDir Path folder) throws Exception {
        File file = folder.resolve("sales.journal").toFile();
        Logger logger = Logger.getLogger("journal-test");
        SaleJournal journal = new SaleJournal(file, logger);
        journal.open();
        UUID hopper = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        ItemStack bones = new ItemStack(Material.BONE, 5);
        journal.sale(new SaleJournal.Entry(hopper, 1, world, 8, 64, 8, owner, 20.0, List.of(new ItemStack(Material.CACTUS, 10)), false))
            .get(5, java.util.concurrent.TimeUnit.SECONDS);
        journal.sale(new SaleJournal.Entry(hopper, 2, world, 8, 64, 8, owner, 4.0, List.of(new ItemStack(Material.CACTUS, 2)), false))
            .get(5, java.util.concurrent.TimeUnit.SECONDS);
        journal.sale(new SaleJournal.Entry(hopper, 3, world, 8, 64, 8, owner, 5.0, List.of(bones), false))
            .get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(Files.readString(file.toPath()).contains("S " + hopper + " 3 "), "a sale is on disk once its future completes");
        journal.voided(hopper, 2);
        journal.saved(hopper, 1);
        journal.flush();
        assertEquals(List.of(2L, 3L), journal.entries(hopper).stream().map(SaleJournal.Entry::seq).toList(), "sale 1 is in the saved world");
        assertTrue(journal.entries(hopper).get(0).voided(), "sale 2 was refused");
        assertEquals(List.of(hopper), journal.hoppersIn(new ChunkId(world, 0, 0)));
        assertTrue(journal.hoppersIn(new ChunkId(world, 1, 0)).isEmpty());
        journal.close();

        // A crash cut the last line short: everything before it still counts.
        Files.writeString(file.toPath(), "S " + hopper + " 4 cut-short", java.nio.file.StandardOpenOption.APPEND);
        SaleJournal again = new SaleJournal(file, logger);
        again.open();
        List<SaleJournal.Entry> entries = again.entries(hopper);
        assertEquals(List.of(2L, 3L), entries.stream().map(SaleJournal.Entry::seq).toList(), "what is not known to be saved survives a restart");
        assertTrue(entries.get(0).voided());
        assertEquals(List.of(bones), entries.get(1).stacks(), "the exact stacks sold");
        assertEquals(5.0, entries.get(1).amount());
        again.forget(hopper);
        again.close();
        SaleJournal empty = new SaleJournal(file, logger);
        empty.open();
        assertTrue(empty.entries(hopper).isEmpty(), "a hopper gone from the saved world keeps no sales");
        assertEquals(0, empty.size());
        empty.close();
    }

    @Test
    void onlyAPickaxeMakesAHopperDropItself() {
        WorldMock world = server.addSimpleWorld("tools");
        Block block = world.getBlockAt(0, 64, 0);
        block.setType(Material.HOPPER);
        assertTrue(Guard.mines(block, new ItemStack(Material.WOODEN_PICKAXE)));
        assertTrue(Guard.mines(block, new ItemStack(Material.NETHERITE_PICKAXE)));
        assertFalse(Guard.mines(block, new ItemStack(Material.DIAMOND_SHOVEL)));
        assertFalse(Guard.mines(block, new ItemStack(Material.AIR)));
        assertFalse(Guard.mines(block, null));
    }
}

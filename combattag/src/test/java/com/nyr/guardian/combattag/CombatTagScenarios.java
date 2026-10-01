package com.nyr.guardian.combattag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.LivingEntityMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.opentest4j.TestAbortedException;

/**
 * What NYR CombatTag Pro must do that MockBukkit can run, through the Bukkit API only, on the compiled classes and on the built
 * jar: a hit tags both players, a blocked command is refused, logging out in combat leaves a dummy carrying the player's health,
 * armour and name, killing it drops the player's kit once and writes the kill record first, and the owner's next join takes
 * the kit away before they die, so their death drops nothing again. The mannequin, the action bar on a real client, restarts
 * and hard kills are proved on live servers by testbed/scenarios/combattag.mjs.
 */
public final class CombatTagScenarios {

    /** A player whose data "saves" (MockBukkit does not implement it) and remembers whether the kill record still existed then. */
    @SuppressWarnings("unchecked")
    public static final class SavingPlayer extends PlayerMock {
        public final AtomicInteger saves = new AtomicInteger();
        public volatile Runnable onSave = () -> { };

        public SavingPlayer(ServerMock server, String name) {
            super(server, name);
        }

        @Override
        public void saveData() {
            onSave.run();
            saves.incrementAndGet();
        }
    }

    /** Every PlayerDeathEvent the scenario sees, with what it would drop and its message. */
    public static final class Deaths implements Listener {
        public final List<PlayerDeathEvent> seen = new ArrayList<>();

        @EventHandler(priority = EventPriority.MONITOR)
        public void onDeath(PlayerDeathEvent event) {
            seen.add(event);
        }
    }

    public static final NamespacedKey MARK = NamespacedKey.fromString("nyrcombattagpro:dummy");

    private final ServerMock server;
    private final Plugin plugin;
    private final WorldMock world;

    public CombatTagScenarios(ServerMock server, Plugin plugin) {
        this.server = server;
        this.plugin = plugin;
        this.world = server.getWorlds().isEmpty() ? server.addSimpleWorld("world") : (WorldMock) server.getWorlds().get(0);
    }

    public void all() {
        try {
            tagRefuseLogOutKillAndRejoin();
            commandsAnswer();
        } catch (TestAbortedException unimplemented) {
            fail("MockBukkit could not run part of the scenario, so it proved nothing: " + unimplemented.getMessage(), unimplemented);
        }
    }

    public SavingPlayer join(String name) {
        SavingPlayer player = new SavingPlayer(server, name);
        server.addPlayer(player);
        player.setGameMode(org.bukkit.GameMode.SURVIVAL);
        return player;
    }

    /** A sword in hand, 32 diamonds and 5 golden apples, an iron helmet and chestplate, a shield: six stacks. */
    public static void kit(Player player) {
        player.getInventory().clear();
        player.getInventory().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
        player.getInventory().addItem(new ItemStack(Material.DIAMOND, 32), new ItemStack(Material.GOLDEN_APPLE, 5));
        player.getInventory().setHelmet(new ItemStack(Material.IRON_HELMET));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
    }

    public void hit(Entity damager, Player victim) {
        server.getPluginManager().callEvent(new EntityDamageByEntityEvent(damager, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0));
    }

    /** Every chat line (and, in MockBukkit, action bar) the player got since the last call, colour codes removed. */
    public static List<String> drain(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        for (String line = player.nextMessage(); line != null; line = player.nextMessage()) {
            lines.add(line.replaceAll("§[0-9a-fk-orxA-FK-ORX]", ""));
        }
        return lines;
    }

    public List<LivingEntity> dummies() {
        List<LivingEntity> out = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (entity instanceof LivingEntity living && entity.isValid() && entity.getPersistentDataContainer().has(MARK, PersistentDataType.STRING)) {
                out.add(living);
            }
        }
        return out;
    }

    /** Stacks of this material lying in the world, counted as items. */
    public int onGround(Material material) {
        int count = 0;
        for (Item item : world.getEntitiesByClass(Item.class)) {
            if (item.isValid() && item.getItemStack().getType() == material) {
                count += item.getItemStack().getAmount();
            }
        }
        return count;
    }

    public static int carried(Player player, Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    public File recordOf(Player player) {
        return new File(plugin.getDataFolder(), "data/killed/" + player.getUniqueId() + ".yml");
    }

    /** Kills a dummy the way the server does: its death event, with drops the game would add (a copy of its helmet). */
    public EntityDeathEvent kill(LivingEntity dummy, Player killer) {
        if (dummy instanceof LivingEntityMock mock) {
            mock.setKiller(killer);
        }
        List<ItemStack> drops = new ArrayList<>(List.of(new ItemStack(Material.IRON_HELMET), new ItemStack(Material.ROTTEN_FLESH, 2)));
        EntityDeathEvent death = new EntityDeathEvent(dummy, DamageSource.builder(DamageType.GENERIC).build(), drops, 5);
        server.getPluginManager().callEvent(death);
        dummy.remove();
        return death;
    }

    void tagRefuseLogOutKillAndRejoin() {
        SavingPlayer kai = join("Kai");
        SavingPlayer luna = join("Luna");
        Deaths deaths = new Deaths();
        server.getPluginManager().registerEvents(deaths, plugin);
        kit(kai);
        kai.setHealth(13);

        hit(luna, kai);
        List<String> told = drain(kai);
        assertTrue(told.stream().anyMatch(line -> line.contains("You are in combat for 15s")), "Kai is told he is in combat: " + told);
        assertTrue(told.stream().anyMatch(line -> line.contains("In combat") && line.contains("15s")), "Kai sees the countdown: " + told);
        assertTrue(drain(luna).stream().anyMatch(line -> line.contains("You are in combat")), "the attacker is tagged too");

        PlayerCommandPreprocessEvent spawn = new PlayerCommandPreprocessEvent(kai, "/spawn");
        server.getPluginManager().callEvent(spawn);
        assertTrue(spawn.isCancelled(), "/spawn is refused in combat");
        assertTrue(drain(kai).stream().anyMatch(line -> line.contains("You cannot use /spawn in combat")));
        PlayerCommandPreprocessEvent message = new PlayerCommandPreprocessEvent(kai, "/msg Luna hi");
        server.getPluginManager().callEvent(message);
        assertFalse(message.isCancelled(), "a command not on the list still works");

        kai.disconnect();
        List<LivingEntity> standing = dummies();
        assertEquals(1, standing.size(), "logging out in combat leaves one dummy");
        LivingEntity dummy = standing.get(0);
        assertEquals(kai.getUniqueId().toString(), dummy.getPersistentDataContainer().get(MARK, PersistentDataType.STRING));
        assertTrue(String.valueOf(customName(dummy)).contains("Kai"), "the dummy is named after Kai: " + customName(dummy));
        assertEquals(13.0, dummy.getHealth(), 0.001, "the dummy has Kai's health");
        assertFalse(dummy.isPersistent(), "a dummy is never saved with its chunk");
        assertNotNull(dummy.getEquipment());
        assertEquals(Material.IRON_HELMET, dummy.getEquipment().getHelmet().getType(), "it wears Kai's helmet");
        assertEquals(Material.IRON_SWORD, dummy.getEquipment().getItemInMainHand().getType(), "it holds Kai's sword");
        assertEquals(32, carried(kai, Material.DIAMOND), "logging out takes nothing from Kai himself");

        EntityDeathEvent death = kill(dummy, luna);
        assertTrue(death.getDrops().isEmpty(), "the dummy's own equipment and loot never drop: " + death.getDrops());
        assertEquals(0, death.getDroppedExp());
        assertEquals(32, onGround(Material.DIAMOND), "Kai's diamonds lie where the dummy died");
        assertEquals(5, onGround(Material.GOLDEN_APPLE));
        assertEquals(1, onGround(Material.IRON_HELMET), "one helmet: Kai's, not also the dummy's copy");
        assertEquals(1, onGround(Material.IRON_SWORD));
        assertEquals(1, onGround(Material.SHIELD));
        assertEquals(0, onGround(Material.ROTTEN_FLESH), "no mob loot");
        File record = recordOf(kai);
        assertTrue(record.isFile(), "the kill is recorded on disk: " + record);
        assertTrue(drain(luna).stream().anyMatch(line -> line.contains("Kai") && line.contains("killed by") && line.contains("Luna")),
            "everyone hears who killed the dummy");

        kai.onSave = () -> assertTrue(record.isFile(), "Kai's emptied inventory is saved before the record is deleted");
        // Since 1.21.5 the game restores who last hit a player when they join; here that is Luna.
        kai.setKiller(luna);
        kai.reconnect();
        assertEquals(0, carried(kai, Material.DIAMOND), "on joining, Kai no longer has what the dummy dropped");
        assertNull(kai.getInventory().getHelmet());
        assertEquals(1, kai.saves.get(), "Kai's emptied inventory was saved");
        assertFalse(record.exists(), "the record is deleted once applied");
        server.getScheduler().performTicks(3);
        assertEquals(1, deaths.seen.size(), "Kai dies on joining");
        PlayerDeathEvent kaiDeath = deaths.seen.get(0);
        assertTrue(kaiDeath.getDrops().stream().noneMatch(item -> item != null && item.getType() != Material.AIR),
            "Kai's death drops nothing again: " + kaiDeath.getDrops());
        @SuppressWarnings("deprecation")
        String deathMessage = kaiDeath.getDeathMessage();
        assertEquals("Kai died in combat: slain by Luna while logged out", deathMessage);
        assertNull(kaiDeath.getEntity().getKiller(), "the death on joining is nobody's kill: Luna was credited once, for the dummy");
        assertEquals(1, luna.getStatistic(org.bukkit.Statistic.PLAYER_KILLS), "Luna has exactly one player kill");
        assertEquals(32, onGround(Material.DIAMOND), "exactly one copy of the kit exists: the one on the ground");
        org.bukkit.event.HandlerList.unregisterAll(deaths);
        kai.disconnect();
        luna.disconnect();
    }

    @SuppressWarnings("deprecation")
    private static String customName(Entity entity) {
        return entity.getCustomName();
    }

    void commandsAnswer() {
        PlayerMock mira = join("Mira");
        assertTrue(mira.performCommand("combattag"));
        assertTrue(drain(mira).stream().anyMatch(line -> line.contains("You are not in combat")), "a player can check their state");
        PlayerMock admin = join("Admin");
        admin.setOp(true);
        assertTrue(admin.performCommand("combattag tag Mira"));
        assertTrue(drain(admin).stream().anyMatch(line -> line.contains("Mira is in combat")));
        assertTrue(mira.performCommand("ct"));
        assertTrue(drain(mira).stream().anyMatch(line -> line.contains("You are in combat for")), "the alias works and shows the timer");
        assertTrue(admin.performCommand("combattag status"));
        List<String> status = drain(admin);
        assertTrue(status.stream().anyMatch(line -> line.contains("NYR CombatTag Pro")), status.toString());
        assertTrue(status.stream().anyMatch(line -> line.contains("in combat now: 1")), status.toString());
        assertTrue(admin.performCommand("combattag untag Mira"));
        assertTrue(drain(admin).stream().anyMatch(line -> line.contains("Mira is no longer in combat")));
        assertTrue(admin.performCommand("combattag dummies"));
        assertTrue(drain(admin).stream().anyMatch(line -> line.contains("Standing dummies")));
        assertTrue(admin.performCommand("combattag reload"));
        assertTrue(drain(admin).stream().anyMatch(line -> line.contains("Reloaded")));
        assertTrue(mira.performCommand("combattag status"));
        assertTrue(drain(mira).stream().anyMatch(line -> line.contains("permission")), "status needs nyrcombattagpro.admin");
        mira.disconnect();
        admin.disconnect();
    }
}

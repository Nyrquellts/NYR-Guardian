package com.nyr.guardian.combattag;

import static com.nyr.guardian.combattag.CombatTagScenarios.carried;
import static com.nyr.guardian.combattag.CombatTagScenarios.drain;
import static com.nyr.guardian.combattag.CombatTagScenarios.kit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.WorldMock;
import be.seeseemelk.mockbukkit.entity.ArrowMock;
import be.seeseemelk.mockbukkit.entity.EggMock;
import be.seeseemelk.mockbukkit.entity.LargeFireballMock;
import be.seeseemelk.mockbukkit.entity.PlayerMock;
import be.seeseemelk.mockbukkit.entity.SmallFireballMock;
import be.seeseemelk.mockbukkit.entity.SnowballMock;
import be.seeseemelk.mockbukkit.entity.TNTPrimedMock;
import be.seeseemelk.mockbukkit.entity.ThrownPotionMock;
import be.seeseemelk.mockbukkit.entity.TridentMock;
import be.seeseemelk.mockbukkit.entity.WindChargeMock;
import com.nyr.guardian.combattag.CombatTagScenarios.Deaths;
import com.nyr.guardian.combattag.CombatTagScenarios.SavingPlayer;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.bukkit.Difficulty;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.Egg;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.Item;
import org.bukkit.entity.LargeFireball;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.SmallFireball;
import org.bukkit.entity.Snowball;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.entity.WindCharge;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CombatTagTest {

    private ServerMock server;
    private WorldMock world;
    private CombatTagPlugin plugin;
    private CombatTagScenarios scenarios;
    private final long[] now = {1_000_000L};
    private final List<String> logged = new ArrayList<>();

    @BeforeEach
    void start() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(CombatTagPlugin.class);
        plugin.clock(() -> now[0]);
        plugin.getLogger().addHandler(new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getLevel() + " " + record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
        scenarios = new CombatTagScenarios(server, plugin);
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void scenariosOnTheCompiledClasses() {
        scenarios.all();
    }

    // --- tagging ---

    private boolean tagged(Player player) {
        return plugin.tags().tagged(player.getUniqueId(), now[0]);
    }

    private void untag(Player... players) {
        for (Player player : players) {
            plugin.tags().untag(player.getUniqueId(), now[0]);
        }
    }

    private EntityDamageByEntityEvent hit(Entity damager, Player victim) {
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(damager, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private void edit(String from, String to) {
        Path config = new File(plugin.getDataFolder(), "config.yml").toPath();
        try {
            String before = Files.readString(config, StandardCharsets.UTF_8);
            String after = before.replace(from, to);
            assertFalse(before.equals(after), "config edit changed nothing: " + from);
            Files.writeString(config, after, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            throw new IllegalStateException(failed);
        }
        plugin.reload(server.getConsoleSender());
    }

    /** MockBukkit's arrows and tridents cannot be given a shooter; these can. */
    @SuppressWarnings("unchecked")
    public static final class ShotArrow extends ArrowMock {
        private ProjectileSource shooter;

        public ShotArrow(ServerMock server, ProjectileSource shooter) {
            super(server, UUID.randomUUID());
            this.shooter = shooter;
        }

        @Override
        public ProjectileSource getShooter() {
            return shooter;
        }

        @Override
        public void setShooter(ProjectileSource shooter) {
            this.shooter = shooter;
        }
    }

    @SuppressWarnings("unchecked")
    public static final class ShotTrident extends TridentMock {
        private ProjectileSource shooter;

        public ShotTrident(ServerMock server, ProjectileSource shooter) {
            super(server, UUID.randomUUID());
            this.shooter = shooter;
        }

        @Override
        public ProjectileSource getShooter() {
            return shooter;
        }

        @Override
        public void setShooter(ProjectileSource shooter) {
            this.shooter = shooter;
        }
    }

    /** MockBukkit cannot spawn primed TNT into a world. */
    public static final class LitTnt extends TNTPrimedMock {
        public LitTnt(ServerMock server) {
            super(server, UUID.randomUUID());
        }
    }

    @Test
    void everyPlayerSourceTagsBothPlayers() {
        PlayerMock kai = scenarios.join("Kai");
        PlayerMock luna = scenarios.join("Luna");
        Location at = kai.getLocation();
        List<Entity> damagers = new ArrayList<>();
        damagers.add(luna);
        damagers.add(new ShotArrow(server, luna));
        damagers.add(new ShotTrident(server, luna));
        // Built directly: MockBukkit cannot spawn all of these into a world, but each takes a shooter.
        for (Projectile projectile : List.<Projectile>of(new SnowballMock(server, UUID.randomUUID()), new EggMock(server, UUID.randomUUID()),
            new SmallFireballMock(server, UUID.randomUUID()), new LargeFireballMock(server, UUID.randomUUID()),
            new WindChargeMock(server, UUID.randomUUID()), new ThrownPotionMock(server, UUID.randomUUID()))) {
            projectile.setShooter(luna);
            damagers.add(projectile);
        }
        assertEquals(List.of(Snowball.class, Egg.class, SmallFireball.class, LargeFireball.class, WindCharge.class, ThrownPotion.class),
            damagers.subList(3, 9).stream().map(entity -> entity.getType().getEntityClass()).toList(), "the projectiles are what they claim");
        TNTPrimed tnt = new LitTnt(server);
        tnt.setSource(luna);
        damagers.add(tnt);
        AreaEffectCloud cloud = world.spawn(at, AreaEffectCloud.class);
        cloud.setSource(luna);
        damagers.add(cloud);
        for (Entity damager : damagers) {
            untag(kai, luna);
            hit(damager, kai);
            assertTrue(tagged(kai), "Kai is tagged by " + damager.getType());
            assertTrue(tagged(luna), "Luna is tagged through " + damager.getType());
        }
    }

    @Test
    void exemptPlayersPlacesAndCancelledHitsTagNobody() {
        PlayerMock kai = scenarios.join("Kai");
        PlayerMock luna = scenarios.join("Luna");

        luna.setGameMode(GameMode.CREATIVE);
        hit(luna, kai);
        assertFalse(tagged(kai) || tagged(luna), "a creative attacker tags nobody");
        luna.setGameMode(GameMode.SURVIVAL);
        kai.setGameMode(GameMode.SPECTATOR);
        hit(luna, kai);
        assertFalse(tagged(kai) || tagged(luna), "a spectator victim tags nobody");
        kai.setGameMode(GameMode.SURVIVAL);

        PermissionAttachment bypass = kai.addAttachment(plugin, "nyrcombattagpro.bypass", true);
        hit(luna, kai);
        assertFalse(tagged(kai) || tagged(luna), "a bypassing player is never tagged, nor is who hit them");
        kai.removeAttachment(bypass);

        Arrow own = new ShotArrow(server, kai);
        hit(own, kai);
        assertFalse(tagged(kai), "shooting yourself is not combat");

        EntityDamageByEntityEvent cancelled = new EntityDamageByEntityEvent(luna, kai, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 1.0);
        cancelled.setCancelled(true);
        server.getPluginManager().callEvent(cancelled);
        assertFalse(tagged(kai) || tagged(luna), "a hit a region plugin cancelled tags nobody");

        Zombie zombie = world.spawn(kai.getLocation(), Zombie.class);
        hit(zombie, kai);
        assertFalse(tagged(kai), "mobs tag nobody unless tag-on.mobs is true");

        edit("  disabled: []", "  disabled:\n    - world");
        hit(luna, kai);
        assertFalse(tagged(kai) || tagged(luna), "nobody is tagged in a disabled world");
    }

    @Test
    void mobsTagOnlyTheirVictimWhenAsked() {
        edit("  mobs: false", "  mobs: true");
        PlayerMock kai = scenarios.join("Kai");
        Zombie zombie = world.spawn(kai.getLocation(), Zombie.class);
        hit(zombie, kai);
        assertTrue(tagged(kai), "a zombie's hit tags its victim");
        untag(kai);
        Skeleton skeleton = world.spawn(kai.getLocation(), Skeleton.class);
        Arrow arrow = new ShotArrow(server, skeleton);
        hit(arrow, kai);
        assertTrue(tagged(kai), "a skeleton's arrow tags its victim");
    }

    private ThrownPotion potion(Player thrower, PotionEffectType effect) {
        ThrownPotion potion = world.spawn(thrower.getLocation(), ThrownPotion.class);
        ItemStack item = new ItemStack(Material.SPLASH_POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.addCustomEffect(new PotionEffect(effect, 200, 0), true);
        item.setItemMeta(meta);
        potion.setItem(item);
        potion.setShooter(thrower);
        return potion;
    }

    @Test
    void harmfulPotionsTagBothButHelpfulOnesDoNot() {
        PlayerMock kai = scenarios.join("Kai");
        PlayerMock luna = scenarios.join("Luna");
        server.getPluginManager().callEvent(new PotionSplashEvent(potion(luna, PotionEffectType.POISON), Map.of(kai, 1.0)));
        assertTrue(tagged(kai) && tagged(luna), "a poison splash from Luna tags both");
        untag(kai, luna);
        server.getPluginManager().callEvent(new PotionSplashEvent(potion(luna, PotionEffectType.INSTANT_HEALTH), Map.of(kai, 1.0)));
        assertFalse(tagged(kai) || tagged(luna), "a healing splash tags nobody");
        server.getPluginManager().callEvent(new PotionSplashEvent(potion(luna, PotionEffectType.WEAKNESS), Map.of(kai, 0.0)));
        assertFalse(tagged(kai) || tagged(luna), "a splash that did not reach Kai (intensity 0) tags nobody");

        AreaEffectCloud cloud = world.spawn(kai.getLocation(), AreaEffectCloud.class);
        cloud.setSource(luna);
        cloud.addCustomEffect(new PotionEffect(PotionEffectType.SLOWNESS, 200, 0), true);
        server.getPluginManager().callEvent(new AreaEffectCloudApplyEvent(cloud, new ArrayList<>(List.of(kai))));
        assertTrue(tagged(kai) && tagged(luna), "a lingering slowness cloud from Luna tags both");
        untag(kai, luna);

        edit("  harmful-potions: true", "  harmful-potions: false");
        server.getPluginManager().callEvent(new PotionSplashEvent(potion(luna, PotionEffectType.POISON), Map.of(kai, 1.0)));
        assertFalse(tagged(kai) || tagged(luna), "potions tag nobody with tag-on.harmful-potions off");
    }

    @Test
    void combatRunsOutWithAMessageAndEveryHitRestartsIt() {
        PlayerMock kai = scenarios.join("Kai");
        PlayerMock luna = scenarios.join("Luna");
        hit(luna, kai);
        drain(kai);
        now[0] += 10_000;
        server.getScheduler().performTicks(20);
        assertTrue(drain(kai).stream().anyMatch(line -> line.contains("In combat") && line.contains("5s")), "the countdown shows 5s left");
        hit(luna, kai);
        now[0] += 10_000;
        server.getScheduler().performTicks(20);
        assertTrue(tagged(kai), "a new hit restarted the 15 seconds");
        now[0] += 5_000;
        server.getScheduler().performTicks(20);
        assertFalse(tagged(kai), "15 seconds after the last hit Kai is out of combat");
        assertTrue(drain(kai).stream().anyMatch(line -> line.contains("You are no longer in combat")));
        now[0] += 5_000;
        server.getScheduler().performTicks(20);
        assertTrue(drain(kai).stream().noneMatch(line -> line.contains("no longer in combat") || line.contains("In combat")),
            "the end is announced once and the countdown stops");
    }

    @Test
    void dyingEndsCombat() {
        PlayerMock kai = scenarios.join("Kai");
        PlayerMock luna = scenarios.join("Luna");
        hit(luna, kai);
        kai.setHealth(0);
        assertFalse(tagged(kai));
        assertTrue(tagged(luna), "the survivor stays in combat");
    }

    // --- commands ---

    private boolean refused(Player player, String line) {
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, line);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }

    @Test
    void blockedCommandsAreRefusedOnlyInCombat() {
        PlayerMock kai = scenarios.join("Kai");
        PlayerMock luna = scenarios.join("Luna");
        assertFalse(refused(kai, "/spawn"), "out of combat /spawn works");
        hit(luna, kai);
        assertTrue(refused(kai, "/spawn"));
        assertTrue(refused(kai, "/essentials:home base"), "a plugin: prefix does not get around the list");
        assertTrue(refused(kai, "/HOME"), "neither does upper case");
        assertTrue(refused(kai, "/tpa Luna"));
        assertFalse(refused(kai, "/msg Luna hi"), "commands not on the list work");
        assertFalse(refused(kai, "/combattag"), "the plugin's own command always works");
        assertTrue(drain(kai).stream().anyMatch(line -> line.contains("You cannot use /home in combat")));
        PermissionAttachment bypass = kai.addAttachment(plugin, "nyrcombattagpro.bypass", true);
        assertFalse(refused(kai, "/spawn"), "bypass never refuses");
        kai.removeAttachment(bypass);

        edit("  mode: blacklist", "  mode: whitelist");
        hit(luna, kai);
        assertTrue(refused(kai, "/msg Luna hi"), "whitelist: anything not listed is refused");
        assertFalse(refused(kai, "/spawn"), "whitelist: listed commands are allowed");
        assertFalse(refused(kai, "/ct"), "whitelist: the plugin's own command still works");
    }

    // --- the dummy ---

    private LivingEntity onlyDummy() {
        List<LivingEntity> dummies = scenarios.dummies();
        assertEquals(1, dummies.size(), "exactly one dummy: " + dummies);
        return dummies.get(0);
    }

    private SavingPlayer taggedWithKit(String name, PlayerMock attacker) {
        SavingPlayer player = scenarios.join(name);
        kit(player);
        hit(attacker, player);
        return player;
    }

    @Test
    void quitInCombatLeavesAFallbackDummyLikeThePlayer() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = scenarios.join("Kai");
        kit(kai);
        kai.getAttribute(Attribute.GENERIC_MAX_HEALTH).setBaseValue(24);
        kai.setHealth(22);
        hit(luna, kai);
        kai.disconnect();
        LivingEntity dummy = onlyDummy();
        assertInstanceOf(Husk.class, dummy, "MockBukkit has no mannequin, so the fallback husk stands in");
        assertFalse(((Mob) dummy).isAware(), "no AI");
        assertTrue(dummy.isSilent());
        assertTrue(dummy.isCustomNameVisible());
        assertEquals(24.0, dummy.getAttribute(Attribute.GENERIC_MAX_HEALTH).getBaseValue(), 0.001, "Kai's max health");
        assertEquals(22.0, dummy.getHealth(), 0.001, "Kai's health");
        assertEquals(Material.IRON_CHESTPLATE, dummy.getEquipment().getChestplate().getType());
        assertEquals(Material.SHIELD, dummy.getEquipment().getItemInOffHand().getType());
        assertEquals(0f, dummy.getEquipment().getHelmetDropChance(), "its equipment never drops");
        assertEquals(0f, dummy.getEquipment().getItemInMainHandDropChance());
        assertEquals(dummy.getLocation().getBlockX(), kai.getLocation().getBlockX(), "it stands where Kai stood");
        assertFalse(tagged(kai), "the owner is untagged after the quit handling");
        assertEquals(1, plugin.dummies().standing().size());
    }

    @Test
    void noDummyWhenUntaggedStoppingKickedDeadOrBypassing() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer calm = scenarios.join("Calm");
        kit(calm);
        calm.disconnect();
        assertTrue(scenarios.dummies().isEmpty(), "logging out out of combat leaves nothing");

        SavingPlayer stopping = taggedWithKit("Stop", luna);
        plugin.stoppingProbe(() -> true);
        stopping.disconnect();
        plugin.stoppingProbe(null);
        assertTrue(scenarios.dummies().isEmpty(), "players leaving because the server stops leave nothing");

        SavingPlayer kicked = taggedWithKit("Kicked", luna);
        server.getPluginManager().callEvent(new PlayerKickEvent(kicked, "kicked", "kicked"));
        kicked.disconnect();
        assertTrue(scenarios.dummies().isEmpty(), "a kicked player leaves nothing (dummy.on-kick: false)");

        SavingPlayer dead = taggedWithKit("Dead", luna);
        dead.setHealth(0);
        plugin.tag(dead, false);
        dead.disconnect();
        assertTrue(scenarios.dummies().isEmpty(), "a dead player (on the respawn screen) leaves nothing");

        SavingPlayer bypassing = scenarios.join("Bypass");
        kit(bypassing);
        bypassing.addAttachment(plugin, "nyrcombattagpro.bypass", true);
        hit(luna, bypassing);
        bypassing.disconnect();
        assertTrue(scenarios.dummies().isEmpty(), "a bypassing player leaves nothing");

        edit("  on-kick: false", "  on-kick: true");
        SavingPlayer kickedAgain = taggedWithKit("Kicked2", luna);
        server.getPluginManager().callEvent(new PlayerKickEvent(kickedAgain, "kicked", "kicked"));
        kickedAgain.disconnect();
        assertEquals(1, scenarios.dummies().size(), "with dummy.on-kick a kicked player does leave one");
    }

    @Test
    void theKillRecordIsOnDiskBeforeAnythingDrops() throws IOException {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        List<String> order = new ArrayList<>();
        FileKillRecords files = new FileKillRecords(plugin.getDataFolder().toPath().resolve("data/killed"), plugin.getLogger());
        plugin.recordStore(new KillRecordStore() {
            @Override
            public void write(KillRecord record) throws IOException {
                order.add("write with " + world.getEntitiesByClass(Item.class).size() + " items on the ground");
                files.write(record);
            }

            @Override
            public KillRecord read(UUID owner) throws IOException {
                return files.read(owner);
            }

            @Override
            public void delete(UUID owner) throws IOException {
                files.delete(owner);
            }

            @Override
            public int pending() {
                return files.pending();
            }
        });
        kai.disconnect();
        scenarios.kill(onlyDummy(), luna);
        assertEquals(List.of("write with 0 items on the ground"), order, "the record is written while nothing has dropped yet");
        assertEquals(32, scenarios.onGround(Material.DIAMOND), "then the kit drops");
        KillRecord record = files.read(kai.getUniqueId());
        assertNotNull(record);
        assertEquals("Luna", record.killer());
        assertEquals(luna.getUniqueId(), record.killerId());
        assertTrue(record.itemsDropped() && record.experienceDropped());
        assertEquals(6, record.stacks(), "diamonds, apples, helmet, chestplate, sword, shield");
    }

    @Test
    void aRecordThatCannotBeWrittenDropsNothingAndTheOwnerKeepsEverything() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        plugin.recordStore(new KillRecordStore() {
            @Override
            public void write(KillRecord record) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public KillRecord read(UUID owner) {
                return null;
            }

            @Override
            public void delete(UUID owner) {
            }

            @Override
            public int pending() {
                return 0;
            }
        });
        kai.disconnect();
        scenarios.kill(onlyDummy(), luna);
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty(), "nothing drops when the kill could not be recorded");
        assertTrue(logged.stream().anyMatch(line -> line.startsWith("SEVERE") && line.contains("Could not write the kill record")), logged.toString());
        Deaths deaths = new Deaths();
        server.getPluginManager().registerEvents(deaths, plugin);
        kai.reconnect();
        server.getScheduler().performTicks(3);
        assertEquals(32, carried(kai, Material.DIAMOND), "Kai keeps his kit");
        assertTrue(deaths.seen.isEmpty(), "and is not killed");
    }

    @Test
    void keepInventoryDropsNothingButTheOwnerStillDies() {
        world.setGameRule(GameRule.KEEP_INVENTORY, true);
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.setLevel(20);
        kai.disconnect();
        scenarios.kill(onlyDummy(), luna);
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty(), "keepInventory: the dummy drops nothing");
        Deaths deaths = new Deaths();
        server.getPluginManager().registerEvents(deaths, plugin);
        kai.reconnect();
        assertEquals(32, carried(kai, Material.DIAMOND), "and Kai keeps his kit");
        assertEquals(20, kai.getLevel(), "and his levels");
        server.getScheduler().performTicks(3);
        assertEquals(1, deaths.seen.size(), "the kill still counts: Kai dies on joining");
    }

    @Test
    void experienceDropsAsForAPlayerAndIsTakenFromTheOwner() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.setLevel(5);
        kai.disconnect();
        scenarios.kill(onlyDummy(), luna);
        int orbs = world.getEntitiesByClass(org.bukkit.entity.ExperienceOrb.class).stream().mapToInt(org.bukkit.entity.ExperienceOrb::getExperience).sum();
        assertEquals(35, orbs, "a player's death drops seven points per level");
        kai.reconnect();
        assertEquals(0, kai.getLevel());
        assertEquals(0, kai.getTotalExperience());
    }

    @Test
    void comingBackFirstTakesTheDummyBackWithItsHealth() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.setHealth(18);
        kai.disconnect();
        LivingEntity dummy = onlyDummy();
        dummy.setHealth(7);
        server.getPluginManager().callEvent(new EntityDamageByEntityEvent(luna, dummy, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 11.0));
        server.getScheduler().performTicks(2);
        kai.reconnect();
        assertEquals(7.0, kai.getHealth(), 0.001, "logging out did not heal Kai");
        server.getScheduler().performTicks(3);
        assertFalse(dummy.isValid(), "the dummy is gone");
        assertTrue(scenarios.dummies().isEmpty());
        assertEquals(7.0, kai.getHealth(), 0.001);
        assertEquals(32, carried(kai, Material.DIAMOND), "Kai keeps his kit");
        assertTrue(tagged(kai), "and is in combat again");
        assertTrue(drain(kai).stream().anyMatch(line -> line.contains("You came back before your dummy died")));
        scenarios.kill(dummy, luna);
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty(), "a dummy that dies after its owner came back drops nothing");
    }

    @Test
    void aDummyThatSurvivesItsTimeVanishesAndTheOwnerKeepsEverything() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.disconnect();
        LivingEntity dummy = onlyDummy();
        now[0] += 29_000;
        server.getScheduler().performTicks(20);
        assertTrue(dummy.isValid(), "still standing after 29 s");
        now[0] += 1_000;
        server.getScheduler().performTicks(20);
        assertFalse(dummy.isValid(), "gone after 30 s");
        scenarios.kill(dummy, luna);
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty(), "a timed-out dummy drops nothing");
        Deaths deaths = new Deaths();
        server.getPluginManager().registerEvents(deaths, plugin);
        kai.reconnect();
        server.getScheduler().performTicks(3);
        assertEquals(32, carried(kai, Material.DIAMOND));
        assertTrue(deaths.seen.isEmpty(), "and Kai lives");
        assertFalse(tagged(kai), "and is not in combat");
    }

    @Test
    void aKillRecordSurvivesARestart() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.disconnect();
        scenarios.kill(onlyDummy(), luna);
        server.getPluginManager().disablePlugin(plugin);
        server.getPluginManager().enablePlugin(plugin);
        assertTrue(plugin.dummies().standing().isEmpty());
        assertEquals(1, plugin.store().pending(), "the record is still there after the restart");
        kai.reconnect();
        assertEquals(0, carried(kai, Material.DIAMOND), "and still applied on the next join");
        assertEquals(0, plugin.store().pending());
    }

    @Test
    void disablingThePluginEndsStandingDummies() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.disconnect();
        LivingEntity dummy = onlyDummy();
        server.getPluginManager().disablePlugin(plugin);
        assertFalse(dummy.isValid(), "a disabled plugin leaves no dummy behind");
        server.getPluginManager().enablePlugin(plugin);
        kai.reconnect();
        assertEquals(32, carried(kai, Material.DIAMOND));
    }

    @Test
    void aReloadKeepsTagsAndStandingDummies() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.disconnect();
        LivingEntity dummy = onlyDummy();
        assertTrue(tagged(luna));
        plugin.reload(server.getConsoleSender());
        assertTrue(dummy.isValid(), "the dummy still stands");
        assertTrue(tagged(luna), "Luna is still in combat");
        scenarios.kill(dummy, luna);
        assertEquals(32, scenarios.onGround(Material.DIAMOND), "and it still works after the reload");
    }

    @Test
    void aPeacefulWorldGetsThePeacefulFallback() {
        edit("peaceful-fallback-entity: VILLAGER", "peaceful-fallback-entity: IRON_GOLEM");
        world.setDifficulty(Difficulty.PEACEFUL);
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.disconnect();
        assertInstanceOf(IronGolem.class, onlyDummy(), "peaceful would remove a husk, so the peaceful fallback stands in");
        Settings settings = plugin.currentSettings();
        assertEquals(org.bukkit.entity.EntityType.HUSK, plugin.body().fallbackFor(settings, Difficulty.EASY));
    }

    /** Another plugin that cancels every spawn, as a mob limiter or a region that denies mobs does. */
    public static final class MobLimiter implements Listener {
        @EventHandler(priority = EventPriority.NORMAL)
        public void onSpawn(EntitySpawnEvent event) {
            event.setCancelled(true);
        }
    }

    @Test
    void anotherPluginCancellingTheSpawnDoesNotStopTheDummy() {
        server.getPluginManager().registerEvents(new MobLimiter(), plugin);
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.disconnect();
        assertEquals(1, scenarios.dummies().size(), "the dummy's spawn is allowed again");
    }

    @Test
    void dummiesNeverTransformAndMarkedBodiesNeverDropTheirCopies() {
        PlayerMock luna = scenarios.join("Luna");
        SavingPlayer kai = taggedWithKit("Kai", luna);
        kai.disconnect();
        LivingEntity dummy = onlyDummy();
        Zombie drowned = world.spawn(dummy.getLocation(), Zombie.class);
        EntityTransformEvent drowning = new EntityTransformEvent(dummy, List.of(drowned), EntityTransformEvent.TransformReason.DROWNED);
        server.getPluginManager().callEvent(drowning);
        assertTrue(drowning.isCancelled(), "a husk dummy does not turn into a zombie");

        Graves graves = new Graves();
        server.getPluginManager().registerEvents(graves, plugin);
        Husk orphan = world.spawn(kai.getLocation(), Husk.class);
        orphan.getPersistentDataContainer().set(CombatTagScenarios.MARK, org.bukkit.persistence.PersistentDataType.STRING, UUID.randomUUID().toString());
        assertTrue(scenarios.kill(orphan, luna).getDrops().isEmpty(), "a marked body nobody owns any more drops nothing");
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());

        scenarios.kill(dummy, luna);
        assertEquals(List.of(0, 0), graves.seen, "a graves plugin listening at NORMAL never sees a dummy's own drops");
        assertEquals(32, scenarios.onGround(Material.DIAMOND), "the owner's kit still drops, as items of its own");
    }

    /** Another plugin that takes death drops into a grave, as graves plugins do (NORMAL priority). */
    public static final class Graves implements Listener {
        public final List<Integer> seen = new ArrayList<>();

        @EventHandler(priority = EventPriority.NORMAL)
        public void onDeath(org.bukkit.event.entity.EntityDeathEvent event) {
            if (!(event.getEntity() instanceof Player)) {
                seen.add(event.getDrops().size());
            }
        }
    }

    @Test
    void theKillerIsCreditedAndStaffAreAlerted() {
        PlayerMock luna = scenarios.join("Luna");
        PlayerMock staff = scenarios.join("Staff");
        staff.setOp(true);
        SavingPlayer kai = taggedWithKit("Kai", luna);
        drain(staff);
        kai.disconnect();
        assertTrue(drain(staff).stream().anyMatch(line -> line.contains("Kai logged out in combat")), "staff hear of the combat log");
        scenarios.kill(onlyDummy(), luna);
        assertTrue(drain(staff).stream().anyMatch(line -> line.contains("Kai's dummy was killed by Luna") && line.contains("6 stacks")));
        assertEquals(1, luna.getStatistic(org.bukkit.Statistic.PLAYER_KILLS), "Luna is credited with a player kill");
    }

    @Test
    void settingsOutOfRangeFallBackToDefaults() {
        edit("combat-seconds: 15", "combat-seconds: 0");
        assertEquals(15, plugin.currentSettings().combatSeconds());
        assertTrue(logged.stream().anyMatch(line -> line.startsWith("WARNING") && line.contains("combat-seconds")), logged.toString());
        edit("fallback-entity: HUSK", "fallback-entity: DIAMOND_SWORD");
        assertEquals(org.bukkit.entity.EntityType.HUSK, plugin.currentSettings().fallbackEntity());
        edit("fallback-entity: DIAMOND_SWORD", "fallback-entity: zombie");
        assertEquals(org.bukkit.entity.EntityType.ZOMBIE, plugin.currentSettings().fallbackEntity());
        assertNull(Settings.parse("ARMOR_STAND"), "an armour stand is not a mob and cannot stand in");
        assertTrue(logged.stream().noneMatch(line -> line.startsWith("SEVERE")), logged.toString());
    }

    @Test
    void commandRootsIgnoreSlashesNamespacesAndCase() {
        assertEquals("home", CommandRules.root("/Essentials:HOME base"));
        assertEquals("spawn", CommandRules.root("/spawn"));
        assertEquals("tpa", CommandRules.root("tpa Luna"));
        assertEquals("", CommandRules.root("/"));
        CommandRules rules = new CommandRules(CommandRules.Mode.BLACKLIST, List.of("/Home", "spawn"));
        assertTrue(rules.blocks("home"));
        assertFalse(rules.blocks("combattag"));
    }
}

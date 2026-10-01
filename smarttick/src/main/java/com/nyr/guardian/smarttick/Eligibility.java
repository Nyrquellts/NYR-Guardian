package com.nyr.guardian.smarttick;

import com.nyr.guardian.common.WorldFilter;
import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;

/**
 * Which villagers may sleep: trading-hall villagers, and nobody whose brain does a job. A villager is eligible when it has a
 * real profession and a job site, no bed (unless allowed), nobody is trading with it or leading it, and it cannot go
 * anywhere: walled in, sitting in a vehicle, or standing still for {@code stationary-minutes}.
 */
final class Eligibility {

    /** Why a villager may or may not sleep; the first three allow it. */
    enum Reason {
        ENCLOSED(true), IN_VEHICLE(true), STILL(true),
        WORLD(false), BABY(false), NO_AI(false), NPC(false), NO_PROFESSION(false), NITWIT(false), IN_BED(false),
        TRADING(false), JUST_TRADED(false), LEASHED(false), WITH_PLAYER(false), NO_JOB_SITE(false), JOB_SITE_GONE(false),
        BED(false), FREE(false);

        private final boolean eligible;

        Reason(boolean eligible) {
            this.eligible = eligible;
        }

        boolean eligible() {
            return eligible;
        }

        /** The messages key that explains it in /smarttick check. */
        String messageKey() {
            return "reason-" + name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
        }
    }

    /** The reason, and the job site it found (null when it has none). */
    record Verdict(Reason reason, Location jobSite) {

        boolean eligible() {
            return reason.eligible();
        }
    }

    /** Blocks the game accepts as a job site. */
    static final Set<Material> WORKSTATIONS = EnumSet.of(Material.COMPOSTER, Material.LECTERN, Material.BARREL,
        Material.BLAST_FURNACE, Material.BREWING_STAND, Material.CARTOGRAPHY_TABLE, Material.CAULDRON, Material.WATER_CAULDRON,
        Material.LAVA_CAULDRON, Material.POWDER_SNOW_CAULDRON, Material.FLETCHING_TABLE, Material.GRINDSTONE, Material.LOOM,
        Material.SMITHING_TABLE, Material.SMOKER, Material.STONECUTTER);

    /** A job site farther than this (in blocks, on any axis) is taken on trust: its block may belong to another region. */
    private static final int CHECKED_JOB_SITE_RANGE = 2;
    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final WorldFilter worlds;
    private final boolean skipVillagersWithBeds;
    private final long stationaryMillis;
    private final Tag<Material> doors;

    Eligibility(WorldFilter worlds, boolean skipVillagersWithBeds, long stationaryMillis) {
        this.worlds = worlds;
        this.skipVillagersWithBeds = skipVillagersWithBeds;
        this.stationaryMillis = stationaryMillis;
        Tag<Material> interactable = null;
        try {
            interactable = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft("mob_interactable_doors"), Material.class);
        } catch (RuntimeException unknown) {
            // older servers: wooden doors are the doors villagers open
        }
        this.doors = interactable != null ? interactable : Tag.WOODEN_DOORS;
    }

    /**
     * Judges one villager on its own thread. {@code stillFor} is how long it has stood within 1.5 blocks of one spot;
     * {@code awakeUntil} keeps it awake after a trade.
     */
    Verdict check(Villager villager, long stillFor, long awakeUntil, long now) {
        World world = villager.getWorld();
        if (!worlds.allows(world.getName())) {
            return new Verdict(Reason.WORLD, null);
        }
        if (!villager.isAdult()) {
            return new Verdict(Reason.BABY, null);
        }
        if (!villager.hasAI()) {
            return new Verdict(Reason.NO_AI, null);
        }
        if (villager.hasMetadata("NPC")) {
            return new Verdict(Reason.NPC, null);
        }
        Villager.Profession profession = villager.getProfession();
        if (profession == Villager.Profession.NONE) {
            return new Verdict(Reason.NO_PROFESSION, null);
        }
        if (profession == Villager.Profession.NITWIT) {
            return new Verdict(Reason.NITWIT, null);
        }
        if (villager.isSleeping()) {
            return new Verdict(Reason.IN_BED, null);
        }
        if (villager.isTrading()) {
            return new Verdict(Reason.TRADING, null);
        }
        if (awakeUntil > now) {
            return new Verdict(Reason.JUST_TRADED, null);
        }
        if (villager.isLeashed()) {
            return new Verdict(Reason.LEASHED, null);
        }
        if (withPlayer(villager)) {
            return new Verdict(Reason.WITH_PLAYER, null);
        }
        Location jobSite = villager.getMemory(MemoryKey.JOB_SITE);
        if (jobSite == null || !world.equals(jobSite.getWorld())) {
            return new Verdict(Reason.NO_JOB_SITE, null);
        }
        Location at = villager.getLocation();
        if (jobSiteGone(world, jobSite, at)) {
            return new Verdict(Reason.JOB_SITE_GONE, jobSite);
        }
        if (skipVillagersWithBeds && villager.getMemory(MemoryKey.HOME) != null) {
            return new Verdict(Reason.BED, jobSite);
        }
        if (villager.isInsideVehicle()) {
            return new Verdict(Reason.IN_VEHICLE, jobSite);
        }
        if (stillFor >= stationaryMillis) {
            return new Verdict(Reason.STILL, jobSite);
        }
        if (enclosed(world, at)) {
            return new Verdict(Reason.ENCLOSED, jobSite);
        }
        return new Verdict(Reason.FREE, jobSite);
    }

    private static boolean withPlayer(Villager villager) {
        for (Entity passenger : villager.getPassengers()) {
            if (passenger instanceof Player) {
                return true;
            }
        }
        Entity vehicle = villager.getVehicle();
        if (vehicle != null) {
            for (Entity passenger : vehicle.getPassengers()) {
                if (passenger instanceof Player) {
                    return true;
                }
            }
        }
        return false;
    }

    /** True when the job site block is within reach to check and is no longer a workstation. */
    private static boolean jobSiteGone(World world, Location jobSite, Location at) {
        int x = jobSite.getBlockX();
        int y = jobSite.getBlockY();
        int z = jobSite.getBlockZ();
        if (Math.abs(x - at.getBlockX()) > CHECKED_JOB_SITE_RANGE || Math.abs(y - at.getBlockY()) > CHECKED_JOB_SITE_RANGE
            || Math.abs(z - at.getBlockZ()) > CHECKED_JOB_SITE_RANGE || !loaded(world, x, z, at)) {
            return false;
        }
        return !WORKSTATIONS.contains(world.getBlockAt(x, y, z).getType());
    }

    /**
     * True when the villager cannot walk out: on each of its four sides the block at its feet or at its head blocks it, and
     * it cannot jump onto a side block because the block above that one, or above its own head, blocks it too. Doors
     * villagers open count as open. A side in an unloaded chunk counts as open.
     */
    boolean enclosed(World world, Location at) {
        int x = at.getBlockX();
        int y = at.getBlockY();
        int z = at.getBlockZ();
        boolean roomToJump = open(world.getBlockAt(x, y + 2, z));
        for (int[] side : SIDES) {
            int sx = x + side[0];
            int sz = z + side[1];
            if (!loaded(world, sx, sz, at)) {
                return false;
            }
            Block feet = world.getBlockAt(sx, y, sz);
            Block head = world.getBlockAt(sx, y + 1, sz);
            boolean feetOpen = open(feet);
            boolean headOpen = open(head);
            if (feetOpen && headOpen) {
                return false;
            }
            if (!feetOpen && headOpen && roomToJump && !tall(feet.getType()) && open(world.getBlockAt(sx, y + 2, sz))) {
                return false;
            }
        }
        return true;
    }

    private boolean open(Block block) {
        return block.isPassable() || doors.isTagged(block.getType());
    }

    /** Fences, walls and fence gates stand 1.5 blocks high: nothing jumps onto them. */
    private static boolean tall(Material type) {
        return Tag.FENCES.isTagged(type) || Tag.WALLS.isTagged(type) || Tag.FENCE_GATES.isTagged(type);
    }

    /** The villager's own chunk is loaded; another chunk is checked before a block is read from it. */
    private static boolean loaded(World world, int blockX, int blockZ, Location at) {
        int cx = blockX >> 4;
        int cz = blockZ >> 4;
        return (cx == at.getBlockX() >> 4 && cz == at.getBlockZ() >> 4) || world.isChunkLoaded(cx, cz);
    }
}

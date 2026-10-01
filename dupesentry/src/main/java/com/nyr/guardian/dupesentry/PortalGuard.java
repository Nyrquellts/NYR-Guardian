package com.nyr.guardian.dupesentry;

import com.nyr.guardian.common.WorldFilter;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.FallingBlock;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPortalEvent;

/**
 * Falling blocks through end portals. When sand, gravel, concrete powder, an anvil or any other falling block goes to or from
 * the End through a portal, the game copies it into the other world and then lets the original finish its tick here, where
 * it lands or drops as an item: one block becomes two.
 *
 * <p>The portal event of a falling block that would cross into or out of the End is cancelled: it stays on this side and
 * lands, or drops, once. End gateways stay inside the End and do not copy anything, so they are left alone; nether portals
 * are left alone unless nether-portals is on.
 */
final class PortalGuard implements Listener {

    private final WorldFilter worlds;
    private final Reporter reporter;
    private final DupeNames names;
    private final boolean netherPortals;

    PortalGuard(WorldFilter worlds, Reporter reporter, DupeNames names, boolean netherPortals) {
        this.worlds = worlds;
        this.reporter = reporter;
        this.names = names;
        this.netherPortals = netherPortals;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        if (!(event.getEntity() instanceof FallingBlock falling)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        World fromWorld = from.getWorld();
        World toWorld = to == null ? null : to.getWorld();
        if (fromWorld == null || toWorld == null || fromWorld.equals(toWorld) || !worlds.allows(fromWorld.getName())) {
            return;
        }
        boolean end = isEnd(fromWorld) || isEnd(toWorld);
        boolean nether = fromWorld.getEnvironment() == World.Environment.NETHER || toWorld.getEnvironment() == World.Environment.NETHER;
        if (end) {
            event.setCancelled(true);
            reporter.stopped(Guard.PORTAL_GRAVITY, names.endPortal(falling.getBlockData().getMaterial()), from);
        } else if (nether && netherPortals) {
            event.setCancelled(true);
        }
    }

    private static boolean isEnd(World world) {
        return world.getEnvironment() == World.Environment.THE_END;
    }
}

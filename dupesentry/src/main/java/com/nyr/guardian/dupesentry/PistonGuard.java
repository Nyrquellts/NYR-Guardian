package com.nyr.guardian.dupesentry;

import com.nyr.guardian.common.WorldFilter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.PistonMoveReaction;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.ItemSpawnEvent;

/**
 * TNT, carpet and rail dupers. A piston reads the blocks it pushes, then turns them into moving blocks one by one; the block
 * updates that this sends can light a pushed TNT or break off a pushed carpet or rail while it still stands where it was, and
 * the piston then places the copy it read at the far end anyway. Both exist.
 *
 * <p>The piston event names every block it is about to push, and also the ones it will break (a pumpkin, a melon, a torch
 * in the way), which are left out: their drop is the only copy. For the rest of that tick, a TNT at one of the pushed spots
 * is not lit (it stays TNT, so the piston moves the only copy) and a carpet, rail or any other pushed block that breaks off
 * there does not drop (the piston already moves it). TNT pushed now and lit on a later tick, where it arrives, is untouched.
 */
final class PistonGuard implements Listener {

    private final TickMemory pushed;
    private final WorldFilter worlds;
    private final Reporter reporter;
    private final DupeNames names;
    private final boolean tnt;
    private final boolean blocks;

    PistonGuard(TickMemory pushed, WorldFilter worlds, Reporter reporter, DupeNames names, boolean tnt, boolean blocks) {
        this.pushed = pushed;
        this.worlds = worlds;
        this.reporter = reporter;
        this.names = names;
        this.tnt = tnt;
        this.blocks = blocks;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExtend(BlockPistonExtendEvent event) {
        remember(event.getBlock(), event.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onRetract(BlockPistonRetractEvent event) {
        remember(event.getBlock(), event.getBlocks());
    }

    private void remember(Block piston, List<Block> moving) {
        if (moving.isEmpty() || !worlds.allows(piston.getWorld().getName())) {
            return;
        }
        Map<TickMemory.Spot, TickMemory.Mark> batch = new HashMap<>();
        for (Block block : moving) {
            if (block.getPistonMoveReaction() == PistonMoveReaction.BREAK) {
                continue; // broken by this push, not moved: its drop is the one copy (melon and pumpkin farms)
            }
            Material material = block.getType();
            if (material == Material.TNT ? tnt : blocks) {
                batch.put(TickMemory.Spot.of(block), new TickMemory.Mark(material));
            }
        }
        pushed.remember(batch, piston.getLocation());
    }

    /** A pushed TNT lit where it stands, in the tick it is pushed: the piston is moving it, so it stays unlit. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPrime(TNTPrimeEvent event) {
        if (pushed.isEmpty()) {
            return;
        }
        Block block = event.getBlock();
        TickMemory.Mark mark = pushed.get(TickMemory.Spot.of(block));
        if (mark == null || mark.material() != Material.TNT || block.getType() != Material.TNT) {
            return;
        }
        event.setCancelled(true);
        reporter.stopped(Guard.PISTON_DUPES, names.tnt(), block.getLocation());
    }

    /** A pushed block dropping where it stood, in the tick it is pushed: the piston is moving it, so the drop is a copy. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(ItemSpawnEvent event) {
        if (pushed.isEmpty()) {
            return;
        }
        Item item = event.getEntity();
        Location at = event.getLocation();
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        TickMemory.Mark mark = pushed.get(TickMemory.Spot.of(world, at));
        if (mark == null || mark.material() == Material.TNT || item.getItemStack().getType() != mark.material()) {
            return;
        }
        // Drops are spawned before the block that breaks is removed, so the pushed block must still stand there. That is only
        // so while the piston moves it: afterwards the spot is air or a moving block. A player's throw never starts inside it.
        Block block = world.getBlockAt(at);
        if (block.getType() != mark.material()) {
            return;
        }
        event.setCancelled(true);
        reporter.stopped(Guard.PISTON_DUPES, names.pushedBlock(mark.material()), block.getLocation());
    }
}

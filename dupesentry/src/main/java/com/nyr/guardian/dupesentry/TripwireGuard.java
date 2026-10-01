package com.nyr.guardian.dupesentry;

import com.nyr.guardian.common.WorldFilter;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.ItemSpawnEvent;

/**
 * Tripwire hook duplicators. When the game updates a hook (its string was tripped, placed or cut), it first sets the hook at
 * the other end of the string, and only then sets this hook again, without checking that it is still there. If setting the
 * other hook takes this hook's support away (it powers dust that opens the door this hook hangs on, for example), this hook
 * breaks off and drops, and is then put back where it was: a hook item and a hook block from one hook.
 *
 * <p>A hook that breaks off (it drops while it still stands where its support is gone) is remembered for the rest of the
 * tick. If it breaks off again in the same tick, that second drop is the copy and is cancelled. If a hook still stands there
 * on the next tick, the game put it back, and it is removed without a drop. A player who places a hook there in between
 * placed a new one, which is left alone. Either way one hook is left, the item, as with Paper's placement validation.
 */
final class TripwireGuard implements Listener {

    private final TickMemory brokenOff;
    private final WorldFilter worlds;
    private final Reporter reporter;
    private final DupeNames names;
    private final Function<UUID, World> worldById;
    private final Predicate<Block> canStay;

    /** {@code canStay} tells whether the hook standing at a block could stay there: BlockData#isSupported on a server. */
    TripwireGuard(TickMemory brokenOff, WorldFilter worlds, Reporter reporter, DupeNames names, Function<UUID, World> worldById,
                  Predicate<Block> canStay) {
        this.brokenOff = brokenOff;
        this.worlds = worlds;
        this.reporter = reporter;
        this.names = names;
        this.worldById = worldById;
        this.canStay = canStay;
    }

    /** The server's own answer: the hook's block data is supported where it stands. */
    static boolean supported(Block block) {
        return block.getBlockData().isSupported(block);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(ItemSpawnEvent event) {
        Item item = event.getEntity();
        if (item.getItemStack().getType() != Material.TRIPWIRE_HOOK) {
            return;
        }
        Location at = event.getLocation();
        World world = at.getWorld();
        if (world == null || !worlds.allows(world.getName())) {
            return;
        }
        // A hook that breaks off drops before it is removed, while it still stands where its support is gone.
        Block block = world.getBlockAt(at);
        if (block.getType() != Material.TRIPWIRE_HOOK || canStay.test(block)) {
            return;
        }
        TickMemory.Spot spot = TickMemory.Spot.of(block);
        TickMemory.Mark mark = brokenOff.get(spot);
        if (mark != null && !mark.released()) {
            mark.increment();
            event.setCancelled(true);
            reporter.stopped(Guard.TRIPWIRE_HOOKS, names.tripwireHook(), block.getLocation());
            return;
        }
        brokenOff.remember(Map.of(spot, new TickMemory.Mark(Material.TRIPWIRE_HOOK)), block.getLocation(), this::putBack);
    }

    /** A player placing a hook where one just broke off places a new hook. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (brokenOff.isEmpty() || event.getBlockPlaced().getType() != Material.TRIPWIRE_HOOK) {
            return;
        }
        TickMemory.Mark mark = brokenOff.get(TickMemory.Spot.of(event.getBlockPlaced()));
        if (mark != null) {
            mark.release();
        }
    }

    /**
     * Next tick: a hook standing where one broke off, and that no player placed, was put back by the game. The first drop was
     * kept and every later one cancelled, so this hook is the copy.
     */
    private void putBack(Map<TickMemory.Spot, TickMemory.Mark> expired) {
        for (Map.Entry<TickMemory.Spot, TickMemory.Mark> entry : expired.entrySet()) {
            if (entry.getValue().released()) {
                continue;
            }
            TickMemory.Spot spot = entry.getKey();
            World world = worldById.apply(spot.world());
            if (world == null) {
                continue;
            }
            Block block = world.getBlockAt(spot.x(), spot.y(), spot.z());
            if (block.getType() == Material.TRIPWIRE_HOOK) {
                block.setType(Material.AIR, true);
                reporter.stopped(Guard.TRIPWIRE_HOOKS, names.tripwireHook(), block.getLocation());
            }
        }
    }
}

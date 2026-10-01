package com.nyr.guardian.dupesentry;

import com.nyr.guardian.common.WorldFilter;
import com.tcoded.folialib.impl.PlatformScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Stale container screens. The game closes a chest's screen once the chest is gone or out of reach, and an animal's once the
 * animal is gone, but only when the screen checks itself on the player's next tick; until then the player can still click in
 * a screen whose container no longer exists.
 *
 * <p>Every container screen a player opens on a block (chest, barrel, furnace, hopper, crafting table and the like) or on an
 * entity (horse, llama, minecart, chest boat) is remembered. The screen is closed the moment its block is broken, blown up
 * or unloaded, its entity dies, is destroyed or leaves for another world, or the player quits, dies, changes world or is
 * teleported more than 8 blocks away from it. Screens other plugins make for their menus belong to no block or entity and are
 * never touched.
 *
 * <p>With cursor-item on, the item a quitting player holds on the cursor goes into their inventory before it is saved, and a
 * dying player's goes with their other drops (or stays, with keepInventory), instead of being left to the closing screen.
 */
final class ContainerGuard implements Listener {

    /** Teleports further than this from an open container close it (the game's own reach check is shorter). */
    private static final double MAX_DISTANCE = 8;

    /** Screens a block opens without a block entity of its own, by InventoryType name (names only, so no version lacks one). */
    private static final Set<String> BLOCK_MENUS = Set.of("WORKBENCH", "ANVIL", "ENCHANTING", "GRINDSTONE", "STONECUTTER",
        "SMITHING", "LOOM", "CARTOGRAPHY", "ENDER_CHEST");

    /** A screen a player has open, and the blocks or entity it belongs to. */
    record Open(Inventory inventory, UUID world, List<TickMemory.Spot> blocks, Entity entity) {
    }

    private final Map<UUID, Open> open = new ConcurrentHashMap<>();
    private final Server server;
    private final PlatformScheduler scheduler;
    private final WorldFilter worlds;
    private final Reporter reporter;
    private final DupeNames names;
    private final boolean cursorItem;

    ContainerGuard(Server server, PlatformScheduler scheduler, WorldFilter worlds, Reporter reporter, DupeNames names, boolean cursorItem) {
        this.server = server;
        this.scheduler = scheduler;
        this.worlds = worlds;
        this.reporter = reporter;
        this.names = names;
        this.cursorItem = cursorItem;
    }

    int tracked() {
        return open.size();
    }

    void clear() {
        open.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || !worlds.allows(player.getWorld().getName())) {
            return;
        }
        Open screen = describe(event.getInventory());
        if (screen == null) {
            open.remove(player.getUniqueId());
        } else {
            open.put(player.getUniqueId(), screen);
        }
    }

    /**
     * The block(s) or entity behind a screen, or null for a screen that belongs to neither (another plugin's menu). Block
     * screens without a block entity (crafting table, anvil, ender chest...) have no holder; their block comes from the
     * inventory's own location.
     */
    static Open describe(Inventory inventory) {
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Entity entity && !(holder instanceof HumanEntity)) {
            return new Open(inventory, entity.getWorld().getUID(), List.of(), entity);
        }
        List<Location> places = new ArrayList<>(2);
        if (holder instanceof DoubleChest chest) {
            for (InventoryHolder side : new InventoryHolder[] {chest.getLeftSide(), chest.getRightSide()}) {
                if (side instanceof BlockState state) {
                    places.add(state.getLocation());
                }
            }
        } else if (holder instanceof BlockState state) {
            places.add(state.getLocation());
        } else if ((holder == null || holder instanceof HumanEntity) && BLOCK_MENUS.contains(inventory.getType().name())) {
            places.add(inventory.getLocation());
        }
        List<TickMemory.Spot> blocks = new ArrayList<>(2);
        UUID world = null;
        for (Location place : places) {
            if (place == null || place.getWorld() == null) {
                continue;
            }
            world = place.getWorld().getUID();
            blocks.add(TickMemory.Spot.of(place.getWorld(), place));
        }
        return blocks.isEmpty() ? null : new Open(inventory, world, List.copyOf(blocks), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Open screen = open.get(event.getPlayer().getUniqueId());
        if (screen != null && screen.inventory().equals(event.getInventory())) {
            open.remove(event.getPlayer().getUniqueId(), screen);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        blockGone(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        for (Block block : event.blockList()) {
            blockGone(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        for (Block block : event.blockList()) {
            blockGone(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onUnload(ChunkUnloadEvent event) {
        if (open.isEmpty()) {
            return;
        }
        Chunk chunk = event.getChunk();
        UUID world = chunk.getWorld().getUID();
        for (Map.Entry<UUID, Open> entry : open.entrySet()) {
            Open screen = entry.getValue();
            for (TickMemory.Spot spot : screen.blocks()) {
                if (spot.world().equals(world) && spot.x() >> 4 == chunk.getX() && spot.z() >> 4 == chunk.getZ()) {
                    close(entry.getKey(), screen);
                    break;
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            entityGone(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        entityGone(event.getVehicle());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTeleport(EntityTeleportEvent event) {
        entityMoved(event.getEntity(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        entityMoved(event.getEntity(), event.getTo());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (cursorItem) {
            ItemStack cursor = takeCursor(player);
            if (cursor != null) {
                for (ItemStack left : player.getInventory().addItem(cursor).values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left);
                }
            }
        }
        Open screen = open.get(player.getUniqueId());
        if (screen != null) {
            close(player.getUniqueId(), screen);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (cursorItem) {
            ItemStack cursor = takeCursor(player);
            if (cursor != null) {
                if (event.getKeepInventory()) {
                    for (ItemStack left : player.getInventory().addItem(cursor).values()) {
                        event.getDrops().add(left);
                    }
                } else {
                    event.getDrops().add(cursor);
                }
            }
        }
        Open screen = open.get(player.getUniqueId());
        if (screen != null) {
            close(player.getUniqueId(), screen);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Open screen = open.get(event.getPlayer().getUniqueId());
        if (screen != null) {
            close(event.getPlayer().getUniqueId(), screen);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Open screen = open.get(event.getPlayer().getUniqueId());
        Location to = event.getTo();
        if (screen == null || to == null) {
            return;
        }
        Location anchor = anchor(screen);
        if (anchor == null || to.getWorld() == null || !to.getWorld().equals(anchor.getWorld())
            || to.distanceSquared(anchor) > MAX_DISTANCE * MAX_DISTANCE) {
            close(event.getPlayer().getUniqueId(), screen);
        }
    }

    private static ItemStack takeCursor(Player player) {
        ItemStack cursor = player.getItemOnCursor();
        if (cursor == null || cursor.getType() == Material.AIR || cursor.getAmount() <= 0) {
            return null;
        }
        player.setItemOnCursor(null);
        return cursor;
    }

    private void blockGone(Block block) {
        if (open.isEmpty()) {
            return;
        }
        TickMemory.Spot spot = TickMemory.Spot.of(block);
        for (Map.Entry<UUID, Open> entry : open.entrySet()) {
            if (entry.getValue().blocks().contains(spot)) {
                close(entry.getKey(), entry.getValue());
            }
        }
    }

    private void entityGone(Entity entity) {
        if (open.isEmpty()) {
            return;
        }
        UUID id = entity.getUniqueId();
        for (Map.Entry<UUID, Open> entry : open.entrySet()) {
            Entity holder = entry.getValue().entity();
            if (holder != null && holder.getUniqueId().equals(id)) {
                close(entry.getKey(), entry.getValue());
            }
        }
    }

    private void entityMoved(Entity entity, Location to) {
        if (open.isEmpty() || entity instanceof HumanEntity) {
            return;
        }
        if (to == null || to.getWorld() == null || !to.getWorld().equals(entity.getWorld())) {
            entityGone(entity);
        }
    }

    private Location anchor(Open screen) {
        if (screen.entity() != null) {
            return screen.entity().getLocation();
        }
        TickMemory.Spot spot = screen.blocks().get(0);
        World world = server.getWorld(spot.world());
        return world == null ? null : new Location(world, spot.x() + 0.5, spot.y() + 0.5, spot.z() + 0.5);
    }

    /**
     * Closes the player's screen on the player's own thread (at once when this thread owns the player, as it does everywhere
     * but Folia), if the player still has that very screen open.
     */
    private void close(UUID playerId, Open screen) {
        Player player = server.getPlayer(playerId);
        if (player == null) {
            open.remove(playerId, screen);
            return;
        }
        Runnable closing = () -> {
            if (open.get(playerId) != screen) {
                return;
            }
            open.remove(playerId, screen);
            if (!screen.inventory().getViewers().contains(player)) {
                return;
            }
            Location where = anchor(screen);
            player.closeInventory();
            reporter.stopped(Guard.CONTAINER_DESYNC, names.container(), where == null ? player.getLocation() : where);
        };
        if (scheduler.isOwnedByCurrentRegion(player)) {
            closing.run();
        } else {
            scheduler.runAtEntity(player, task -> closing.run());
        }
    }
}

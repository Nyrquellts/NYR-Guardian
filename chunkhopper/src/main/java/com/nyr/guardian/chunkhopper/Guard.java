package com.nyr.guardian.chunkhopper;

import com.nyr.guardian.common.Messages;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachmentInfo;

/**
 * Placing, breaking and protecting Chunk Hoppers, and following their chunks as they load and unload.
 *
 * <ul>
 *   <li>Placing: one per chunk, the per-player limit, allowed worlds.</li>
 *   <li>Breaking: only the owner or staff; the drop is a Chunk Hopper item again (only when the block would have dropped
 *   itself: never in creative, never without a pickaxe), and the contents drop as vanilla drops them, once.</li>
 *   <li>Explosions and mobs leave Chunk Hoppers standing (explosion-proof).</li>
 * </ul>
 */
final class Guard implements Listener {

    private final Hoppers hoppers;
    private final HopperItems items;
    private final Messages messages;
    private final String usePermission;
    private final String adminPermission;
    private final String limitPrefix;
    private final int perPlayer;
    private final boolean protectFromOthers;
    private final boolean explosionProof;
    private final boolean autoSellDefault;
    /** Chunk Hoppers broken this tick whose block drop is to become a Chunk Hopper item: when they were broken. */
    private final Map<BlockPos, Long> dropsItself = new ConcurrentHashMap<>();

    Guard(Hoppers hoppers, HopperItems items, Messages messages, String usePermission, String adminPermission, String limitPrefix,
          int perPlayer, boolean protectFromOthers, boolean explosionProof, boolean autoSellDefault) {
        this.hoppers = hoppers;
        this.items = items;
        this.messages = messages;
        this.usePermission = usePermission;
        this.adminPermission = adminPermission;
        this.limitPrefix = limitPrefix;
        this.perPlayer = perPlayer;
        this.protectFromOthers = protectFromOthers;
        this.explosionProof = explosionProof;
        this.autoSellDefault = autoSellDefault;
    }

    // ------------------------------------------------------------------ placing

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!items.isChunkHopper(event.getItemInHand())) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        String refusal = refusal(player, block);
        if (refusal != null) {
            event.setCancelled(true);
        }
    }

    /** Why this player may not place a Chunk Hopper here (after telling them), or null when they may. */
    private String refusal(Player player, Block block) {
        World world = block.getWorld();
        if (block.getType() != Material.HOPPER) {
            return "not a hopper";
        }
        if (!hoppers.worldAllowed(world)) {
            messages.send(player, "world-disabled");
            return "world";
        }
        if (!player.hasPermission(usePermission)) {
            messages.send(player, "no-permission");
            return "permission";
        }
        int[] other = occupied(block);
        if (other != null) {
            messages.send(player, "one-per-chunk", "x", other[0], "y", other[1], "z", other[2]);
            return "one per chunk";
        }
        int limit = limit(player);
        if (limit >= 0) {
            int owned = hoppers.registry.count(player.getUniqueId());
            if (owned >= limit) {
                messages.send(player, "limit-reached", "count", owned);
                return "limit";
            }
        }
        return null;
    }

    /** Where another Chunk Hopper stands in this block's chunk, or null. Stale pointers are cleaned up on the way. */
    private int[] occupied(Block block) {
        World world = block.getWorld();
        HopperRecord record = hoppers.at(world, block.getX(), block.getZ());
        if (record != null && !record.at(block)) {
            if (hoppers.live(record, world) != null) {
                return new int[] {record.x, record.y, record.z};
            }
        }
        // The chunk may have loaded this very tick, before its hopper was read.
        int[] marked = hoppers.store.marked(block.getChunk());
        if (marked != null && !(marked[0] == block.getX() && marked[1] == block.getY() && marked[2] == block.getZ())) {
            if (hoppers.store.read(world.getBlockAt(marked[0], marked[1], marked[2])) != null) {
                return marked;
            }
            hoppers.store.unmark(block.getChunk(), marked[0], marked[1], marked[2]);
        }
        return null;
    }

    /** How many Chunk Hoppers this player may own: -1 for no limit. */
    int limit(Player player) {
        if (player.hasPermission(limitPrefix + "unlimited")) {
            return -1;
        }
        int best = -1;
        for (PermissionAttachmentInfo info : player.getEffectivePermissions()) {
            String node = info.getPermission();
            if (info.getValue() && node.startsWith(limitPrefix)) {
                try {
                    best = Math.max(best, Integer.parseInt(node.substring(limitPrefix.length())));
                } catch (NumberFormatException notANumber) {
                    // nyrchunkhopper.limit.unlimited is handled above; anything else is not a limit
                }
            }
        }
        if (best >= 0) {
            return best;
        }
        return perPlayer > 0 ? perPlayer : -1;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlaced(BlockPlaceEvent event) {
        if (!items.isChunkHopper(event.getItemInHand())) {
            return;
        }
        Block block = event.getBlockPlaced();
        HopperRecord existing = hoppers.at(block.getWorld(), block.getX(), block.getZ());
        if (block.getType() != Material.HOPPER || !hoppers.worldAllowed(block.getWorld()) || (existing != null && !existing.at(block))) {
            return;
        }
        if (hoppers.create(block, event.getPlayer(), autoSellDefault) != null) {
            messages.send(event.getPlayer(), "placed");
        }
    }

    // ------------------------------------------------------------------ breaking

    /**
     * Only the owner or staff break a Chunk Hopper, and (outside creative) only with a pickaxe: by hand vanilla would drop
     * nothing, and the Chunk Hopper would be lost.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.HOPPER) {
            return;
        }
        HopperRecord record = hoppers.at(block);
        if (record == null) {
            return;
        }
        Player player = event.getPlayer();
        if (protectFromOthers && !record.ownedBy(player.getUniqueId()) && !player.hasPermission(adminPermission)) {
            event.setCancelled(true);
            messages.send(player, "not-yours", "owner", record.ownerName());
            return;
        }
        if (player.getGameMode() != GameMode.CREATIVE && !mines(block, player.getInventory().getItemInMainHand())) {
            event.setCancelled(true);
            messages.send(player, "need-pickaxe");
        }
    }

    /** Whether the tool makes a hopper drop itself: the server's own answer, or any pickaxe where it cannot say. */
    static boolean mines(Block block, ItemStack tool) {
        try {
            return block.isPreferredTool(tool);
        } catch (RuntimeException | LinkageError unanswered) {
            return tool != null && tool.getType().name().endsWith("_PICKAXE");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBroken(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.HOPPER) {
            return;
        }
        HopperRecord record = hoppers.at(block);
        if (record == null) {
            return;
        }
        Player player = event.getPlayer();
        // Vanilla drops the hopper block only outside creative, with drops on, and mined with a pickaxe.
        if (player.getGameMode() != GameMode.CREATIVE && event.isDropItems() && mines(block, player.getInventory().getItemInMainHand())) {
            dropsItself.put(BlockPos.of(block), System.nanoTime());
        }
        hoppers.removed(record, block.getWorld());
        messages.send(player, "broken");
    }

    /**
     * The block's own drop becomes a Chunk Hopper item. Paper lists the hopper's contents here too, before the block's own
     * drop; Spigot drops the contents on its own. The block's own drop is the last plain hopper in the list, and it carries
     * the placed hopper's name (the game copies it), which no hopper a player renamed can have.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    @SuppressWarnings("deprecation") // legacy String names: the Adventure methods do not exist on Spigot
    public void onDrops(BlockDropItemEvent event) {
        Block block = event.getBlock();
        if (dropsItself.remove(BlockPos.of(block)) == null) {
            return;
        }
        BlockState state = event.getBlockState();
        if (!(state instanceof Hopper hopper) || !hoppers.store.isChunkHopper(hopper)) {
            return;
        }
        String name = hopper.getCustomName();
        List<Item> dropped = event.getItems();
        hoppers.stats.hopperBreaks.increment();
        hoppers.stats.hopperBreakItems.add(dropped.size());
        for (int i = dropped.size() - 1; i >= 0; i--) {
            Item item = dropped.get(i);
            ItemStack stack = item.getItemStack();
            if (stack.getType() != Material.HOPPER || stack.getAmount() != 1 || items.isChunkHopper(stack)) {
                continue;
            }
            if (name != null && !name.isEmpty()) {
                ItemMeta meta = stack.getItemMeta();
                if (meta == null || !meta.hasDisplayName() || !name.equals(meta.getDisplayName())) {
                    continue;
                }
            }
            item.setItemStack(items.create(1));
            return;
        }
    }

    /** A pending block-drop conversion that never saw its BlockDropItemEvent (another plugin took the drops) is dropped. */
    void purge() {
        long now = System.nanoTime();
        dropsItself.values().removeIf(at -> now - at > 2_000_000_000L);
    }

    // ------------------------------------------------------------------ explosions and mobs

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        spare(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        spare(event.blockList());
    }

    /** Explosion-proof: Chunk Hoppers leave the list of blocks an explosion breaks. */
    private void spare(List<Block> blocks) {
        if (!explosionProof) {
            return;
        }
        for (Iterator<Block> each = blocks.iterator(); each.hasNext(); ) {
            Block block = each.next();
            if (block.getType() == Material.HOPPER && hoppers.at(block) != null) {
                each.remove();
                hoppers.stats.explosionsBlocked.increment();
            }
        }
    }

    /** Without explosion-proof, a Chunk Hopper an explosion breaks is forgotten (it drops as a plain hopper). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExploded(EntityExplodeEvent event) {
        forgetExploded(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExploded(BlockExplodeEvent event) {
        forgetExploded(event.blockList());
    }

    private void forgetExploded(List<Block> blocks) {
        for (Block block : blocks) {
            if (block.getType() == Material.HOPPER) {
                HopperRecord record = hoppers.at(block);
                if (record != null) {
                    hoppers.removed(record, block.getWorld());
                }
            }
        }
    }

    /** A wither (or any mob) breaking or changing a Chunk Hopper is stopped when explosion-proof is on. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        if (explosionProof && block.getType() == Material.HOPPER && !(event.getEntity() instanceof Player) && hoppers.at(block) != null) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ chunks

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        hoppers.chunkLoaded(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        hoppers.chunkUnloading(event.getChunk());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        hoppers.worldUnloading(event.getWorld());
    }

    /** A save: the sales it writes stop needing their journal records once it is on disk. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldSave(WorldSaveEvent event) {
        hoppers.worldSaving(event.getWorld());
    }
}

package com.nyr.guardian.chunkhopper;

import com.nyr.guardian.common.Alerts;
import com.nyr.guardian.common.Messages;
import com.nyr.guardian.common.Text;
import com.tcoded.folialib.impl.PlatformScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Hopper;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * The filter menu: shift + right-click a Chunk Hopper with an empty hand. The top row is the nine filter slots, the bottom
 * row shows the hopper's owner and counters, the auto-sell switch (with an economy) and a clear button.
 *
 * <p>Every icon is made by the plugin and carries the ghost key. Every click that touches the menu is cancelled, as is
 * every click in the player's own inventory that could reach it (shift-click, double-click collect); drags onto it are
 * cancelled. A shift-click on an item of your own adds its material to the filter; nothing moves. When the menu closes,
 * any icon found on the player anyway is removed and reported, so a way around the cancelling could not keep one.</p>
 *
 * <p>Clicks arrive on the player's thread; changes to the hopper run on the hopper's thread and each open menu is then
 * redrawn on its viewer's thread.</p>
 */
final class Menus implements Listener {

    static final int SIZE = 18;
    static final int INFO = 9;
    static final int SELL = 13;
    static final int CLEAR = 17;

    /** One player's open filter menu. */
    static final class FilterMenu implements InventoryHolder {
        final HopperRecord record;
        final UUID viewer;
        private Inventory inventory;

        FilterMenu(HopperRecord record, UUID viewer) {
            this.record = record;
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Server server;
    private final PlatformScheduler scheduler;
    private final Hoppers hoppers;
    private final HopperItems items;
    private final Seller seller;
    private final Messages messages;
    private final ConfigurationSection texts;
    private final Alerts alerts;
    private final Stats stats;
    private final String adminPermission;
    private final String usePermission;
    private final Map<UUID, FilterMenu> open = new ConcurrentHashMap<>();

    Menus(Server server, PlatformScheduler scheduler, Hoppers hoppers, HopperItems items, Seller seller, Messages messages,
          ConfigurationSection texts, Alerts alerts, Stats stats, String usePermission, String adminPermission) {
        this.server = server;
        this.scheduler = scheduler;
        this.hoppers = hoppers;
        this.items = items;
        this.seller = seller;
        this.messages = messages;
        this.texts = texts;
        this.alerts = alerts;
        this.stats = stats;
        this.usePermission = usePermission;
        this.adminPermission = adminPermission;
    }

    boolean mayEdit(Player player, HopperRecord record) {
        return record.ownedBy(player.getUniqueId()) ? player.hasPermission(usePermission) : player.hasPermission(adminPermission);
    }

    // ------------------------------------------------------------------ opening

    /** Shift + right-click with an empty main hand opens the menu instead of the hopper; with an item, vanilla places it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.HOPPER) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking()) {
            return;
        }
        HopperRecord record = hoppers.at(block);
        if (record == null) {
            return;
        }
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main != null && !main.getType().isAir()) {
            return;
        }
        // Neither hand does anything else to a Chunk Hopper while its menu is the answer (vanilla would open the hopper
        // for an empty hand, or place the off-hand item).
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!mayEdit(player, record)) {
            messages.send(player, "not-yours", "owner", record.ownerName());
            return;
        }
        if (hoppers.live(record, block.getWorld()) == null) {
            return;
        }
        open(player, record);
    }

    @SuppressWarnings("deprecation") // a String title: the Adventure one does not exist on Spigot
    void open(Player player, HopperRecord record) {
        FilterMenu menu = new FilterMenu(record, player.getUniqueId());
        menu.inventory = server.createInventory(menu, SIZE, text("menu-title"));
        render(menu);
        open.put(player.getUniqueId(), menu);
        player.openInventory(menu.inventory);
    }

    /** Draws the menu from its record; on the viewer's thread. */
    void render(FilterMenu menu) {
        Inventory inventory = menu.inventory;
        HopperRecord record = menu.record;
        Filter filter = record.filter();
        for (int slot = 0; slot < Filter.SLOTS; slot++) {
            Material material = filter.slot(slot);
            inventory.setItem(slot, material == null ? null : items.icon(material, null, lines("menu-filter-lore")));
        }
        ItemStack filler = items.icon(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = Filter.SLOTS; slot < SIZE; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(INFO, items.icon(Material.BOOK, text("menu-info"), lines("menu-info-lore",
            "owner", record.ownerName(), "collected", record.collected(), "earned", seller.payout().format(record.earned()),
            "filter", filter.describe(messages.format("filter-everything")))));
        if (seller.canSell()) {
            boolean on = record.autoSell();
            inventory.setItem(SELL, items.icon(on ? Material.LIME_DYE : Material.GRAY_DYE, text(on ? "menu-sell-on" : "menu-sell-off"),
                lines(on ? "menu-sell-on-lore" : "menu-sell-off-lore", "interval", seller.intervalSeconds())));
        }
        inventory.setItem(CLEAR, items.icon(Material.BARRIER, text("menu-clear"), lines("menu-clear-lore")));
    }

    private String text(String key) {
        return Text.color(texts == null ? key : texts.getString(key, key));
    }

    private List<String> lines(String key, Object... pairs) {
        List<String> out = new ArrayList<>();
        if (texts != null) {
            for (String line : texts.getStringList(key)) {
                out.add(Text.color(Text.fill(line, pairs)));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FilterMenu menu)) {
            return;
        }
        int raw = event.getRawSlot();
        int top = event.getInventory().getSize();
        InventoryAction action = event.getAction();
        ClickType click = event.getClick();
        if (raw < 0 || raw >= top) {
            // The player's own inventory, or outside the window.
            if (action == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
                event.setCancelled(true);
                ItemStack clicked = event.getCurrentItem();
                if (clicked != null && !clicked.getType().isAir() && !items.isGhost(clicked) && event.getWhoClicked() instanceof Player player) {
                    Material material = clicked.getType();
                    change(menu, player, filter -> filter.adding(material));
                }
            } else if (reachesTop(action, click)) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || (click != ClickType.LEFT && click != ClickType.RIGHT)) {
            return;
        }
        if (raw < Filter.SLOTS) {
            ItemStack cursor = event.getCursor();
            if (cursor == null || cursor.getType().isAir()) {
                change(menu, player, filter -> filter.without(raw));
            } else if (!items.isGhost(cursor)) {
                Material material = cursor.getType();
                change(menu, player, filter -> filter.with(raw, material));
            }
        } else if (raw == SELL && seller.canSell()) {
            edit(menu, player, record -> record.autoSell(!record.autoSell()));
        } else if (raw == CLEAR) {
            change(menu, player, filter -> Filter.EMPTY);
        }
    }

    /** Clicks in the player's own inventory that can still move items into (or out of) the menu. */
    private static boolean reachesTop(InventoryAction action, ClickType click) {
        return action == InventoryAction.COLLECT_TO_CURSOR || action == InventoryAction.UNKNOWN
            || click == ClickType.DOUBLE_CLICK || click == ClickType.UNKNOWN;
    }

    /** Whatever ran in between, a click that touches the menu stays cancelled. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void keepCancelled(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FilterMenu)) {
            return;
        }
        int raw = event.getRawSlot();
        boolean top = raw >= 0 && raw < event.getInventory().getSize();
        if (top || event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY || reachesTop(event.getAction(), event.getClick())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof FilterMenu)) {
            return;
        }
        int top = event.getInventory().getSize();
        for (int raw : event.getRawSlots()) {
            if (raw < top) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void keepDragCancelled(InventoryDragEvent event) {
        onDrag(event);
    }

    /** Changes the filter on the hopper's thread; tells the player when all nine slots are taken. */
    private void change(FilterMenu menu, Player player, UnaryOperator<Filter> edit) {
        edit(menu, player, record -> {
            Filter next = edit.apply(record.filter());
            if (next == null) {
                messages.send(player, "filter-full");
                return;
            }
            record.filter(next);
        });
    }

    private void edit(FilterMenu menu, Player player, java.util.function.Consumer<HopperRecord> edit) {
        HopperRecord record = menu.record;
        if (!mayEdit(player, record)) {
            messages.send(player, "not-yours", "owner", record.ownerName());
            scheduler.runAtEntity(player, ignored -> player.closeInventory());
            return;
        }
        hoppers.runAt(record, () -> {
            World world = hoppers.world(record);
            if (world == null || !hoppers.current(record)) {
                return;
            }
            Hopper hopper = hoppers.live(record, world);
            if (hopper == null) {
                return;
            }
            Filter before = record.filter();
            boolean sellBefore = record.autoSell();
            edit.accept(record);
            if (!before.equals(record.filter()) || sellBefore != record.autoSell()) {
                hoppers.save(record, world);
            }
            refresh(record);
        });
    }

    /** Redraws every open menu of this hopper, each on its viewer's thread. */
    void refresh(HopperRecord record) {
        for (FilterMenu menu : open.values()) {
            if (menu.record != record) {
                continue;
            }
            Player viewer = server.getPlayer(menu.viewer);
            if (viewer == null) {
                continue;
            }
            onViewer(viewer, () -> {
                if (open.get(viewer.getUniqueId()) == menu) {
                    render(menu);
                }
            });
        }
    }

    /** Closes every open menu of this hopper (it was broken or unloaded). */
    void closeAll(HopperRecord record) {
        for (FilterMenu menu : new ArrayList<>(open.values())) {
            if (menu.record.x != record.x || menu.record.y != record.y || menu.record.z != record.z || !menu.record.world.equals(record.world)) {
                continue;
            }
            Player viewer = server.getPlayer(menu.viewer);
            if (viewer != null) {
                onViewer(viewer, () -> {
                    if (open.get(viewer.getUniqueId()) == menu) {
                        viewer.closeInventory();
                    }
                });
            }
        }
    }

    /**
     * Closes every open menu (the plugin is stopping). The icons are taken out first, here and now: on Folia a viewer's
     * menu closes on their own thread a moment later, after this plugin's listeners are gone, and an open menu without
     * them would let its icons be taken.
     */
    void closeEverything() {
        for (FilterMenu menu : new ArrayList<>(open.values())) {
            if (menu.inventory != null) {
                menu.inventory.clear();
            }
            Player viewer = server.getPlayer(menu.viewer);
            if (viewer != null) {
                onViewer(viewer, viewer::closeInventory);
            }
        }
        open.clear();
    }

    private void onViewer(Player viewer, Runnable task) {
        if (scheduler.isOwnedByCurrentRegion(viewer)) {
            task.run();
        } else {
            scheduler.runAtEntity(viewer, ignored -> task.run());
        }
    }

    // ------------------------------------------------------------------ closing

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof FilterMenu menu)) {
            return;
        }
        HumanEntity who = event.getPlayer();
        open.remove(who.getUniqueId(), menu);
        int removed = removeGhosts(who);
        if (removed > 0) {
            stats.ghostsRemoved.add(removed);
            alerts.send("ghost:" + who.getUniqueId(), messages.format("alert-ghost", "player", who.getName(), "count", removed));
        }
    }

    /** Removes every menu icon from the player's inventory and cursor; how many items that was. */
    int removeGhosts(HumanEntity who) {
        int removed = 0;
        PlayerInventory inventory = who.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (items.isGhost(contents[slot])) {
                removed += contents[slot].getAmount();
                inventory.setItem(slot, null);
            }
        }
        ItemStack cursor = who.getItemOnCursor();
        if (items.isGhost(cursor)) {
            removed += cursor.getAmount();
            who.setItemOnCursor(null);
        }
        return removed;
    }

    int openCount() {
        return open.size();
    }
}

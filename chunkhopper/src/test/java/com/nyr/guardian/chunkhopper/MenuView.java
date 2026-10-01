package com.nyr.guardian.chunkhopper;

import be.seeseemelk.mockbukkit.inventory.PlayerInventoryViewMock;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;

/**
 * A container view with the server's raw-slot layout: the top inventory, then the player's 27 storage slots (inventory
 * slots 9..35), then the hotbar (0..8). MockBukkit leaves reading slots through a view unimplemented, and every click event
 * reads its slot; the cursor is the player's own, as on a server.
 */
public final class MenuView extends PlayerInventoryViewMock {

    private static final int PLAYER_SLOTS = 36;

    public MenuView(HumanEntity player, Inventory top) {
        super(player, top);
    }

    private int topSize() {
        return getTopInventory().getSize();
    }

    /** The index inside {@link #getInventory(int)} for a raw slot, or -1 outside the view. */
    private int slotOf(int raw) {
        int top = topSize();
        if (raw < 0) {
            return -1;
        }
        if (raw < top) {
            return raw;
        }
        int bottom = raw - top;
        if (bottom < 27) {
            return bottom + 9;
        }
        if (bottom < PLAYER_SLOTS) {
            return bottom - 27;
        }
        return -1;
    }

    /** The raw slot of a player inventory slot (0..35) in this view. */
    public int rawOf(int playerSlot) {
        return playerSlot < 9 ? topSize() + 27 + playerSlot : topSize() + playerSlot - 9;
    }

    @Override
    public Inventory getInventory(int raw) {
        if (raw < 0 || raw >= countSlots()) {
            return null;
        }
        return raw < topSize() ? getTopInventory() : getBottomInventory();
    }

    @Override
    public int convertSlot(int raw) {
        int slot = slotOf(raw);
        return slot < 0 ? raw : slot;
    }

    @Override
    public int countSlots() {
        return topSize() + PLAYER_SLOTS;
    }

    @Override
    public ItemStack getItem(int raw) {
        Inventory inventory = getInventory(raw);
        int slot = slotOf(raw);
        return inventory == null || slot < 0 ? null : inventory.getItem(slot);
    }

    @Override
    public void setItem(int raw, ItemStack item) {
        Inventory inventory = getInventory(raw);
        int slot = slotOf(raw);
        if (inventory != null && slot >= 0) {
            inventory.setItem(slot, item);
        }
    }

    @Override
    public ItemStack getCursor() {
        return getPlayer().getItemOnCursor();
    }

    @Override
    public void setCursor(ItemStack item) {
        getPlayer().setItemOnCursor(item);
    }

    @Override
    public InventoryType.SlotType getSlotType(int raw) {
        if (raw == InventoryView.OUTSIDE || getInventory(raw) == null) {
            return InventoryType.SlotType.OUTSIDE;
        }
        return raw >= topSize() && slotOf(raw) < 9 ? InventoryType.SlotType.QUICKBAR : InventoryType.SlotType.CONTAINER;
    }

    @Override
    public void close() {
        getPlayer().closeInventory();
    }

    @Override
    public boolean setProperty(Property prop, int value) {
        return false;
    }
}

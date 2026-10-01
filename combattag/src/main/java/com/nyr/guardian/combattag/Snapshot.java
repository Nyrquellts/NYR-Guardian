package com.nyr.guardian.combattag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * What a player carried when they logged out in combat: copies, taken on the player's own thread as they left. The player's
 * own inventory is not touched; it stays with them in their saved data.
 */
record Snapshot(UUID id, String name, Location location, ItemStack[] storage, ItemStack[] armor, ItemStack offHand, ItemStack mainHand,
                int level, double health, double maxHealth) {

    static Snapshot of(Player player, MaxHealth maxHealth) {
        PlayerInventory inventory = player.getInventory();
        return new Snapshot(player.getUniqueId(), player.getName(), player.getLocation().clone(),
            copies(inventory.getStorageContents()), copies(inventory.getArmorContents()), copy(inventory.getItemInOffHand()),
            copy(inventory.getItemInMainHand()), player.getLevel(), player.getHealth(), maxHealth.of(player));
    }

    private static ItemStack[] copies(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items == null ? 0 : items.length];
        for (int i = 0; i < out.length; i++) {
            out[i] = copy(items[i]);
        }
        return out;
    }

    static ItemStack copy(ItemStack item) {
        return present(item) ? item.clone() : null;
    }

    static boolean present(ItemStack item) {
        return item != null && item.getType() != Material.AIR && item.getAmount() > 0;
    }

    /** Every stack the player carried (inventory, armour, off hand), as the drops of their death. */
    List<ItemStack> drops() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack[] part : new ItemStack[][] {storage, armor}) {
            for (ItemStack item : part) {
                if (present(item)) {
                    out.add(item.clone());
                }
            }
        }
        if (present(offHand)) {
            out.add(offHand.clone());
        }
        return out;
    }

    /** Experience a player's death drops: seven points per level, at most 100, as the game does. */
    int experienceDrop() {
        return Math.min(Math.max(0, level) * 7, 100);
    }

    /** Armour as worn: boots, leggings, chestplate, helmet (the order getArmorContents gives). */
    ItemStack armor(int index) {
        return index < armor.length ? copy(armor[index]) : null;
    }
}

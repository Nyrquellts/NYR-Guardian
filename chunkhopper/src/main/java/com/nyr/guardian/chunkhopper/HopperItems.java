package com.nyr.guardian.chunkhopper;

import com.nyr.guardian.common.Text;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * The Chunk Hopper item and the filter menu's icons. Every icon carries the ghost key, so one that ever reached a player can
 * be recognised and taken away again.
 */
final class HopperItems {

    private final Keys keys;
    private final String name;
    private final List<String> lore;
    private final boolean glint;

    HopperItems(Keys keys, String name, List<String> lore, boolean glint) {
        this.keys = keys;
        this.name = Text.color(name);
        List<String> colored = new ArrayList<>();
        for (String line : lore) {
            colored.add(Text.color(line));
        }
        this.lore = List.copyOf(colored);
        this.glint = glint;
    }

    /** {@code amount} Chunk Hopper items in one stack (at most 64). */
    @SuppressWarnings("deprecation") // legacy String names: the Adventure methods do not exist on Spigot
    ItemStack create(int amount) {
        ItemStack stack = new ItemStack(Material.HOPPER, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            meta.setLore(lore);
            if (glint) {
                shimmer(meta);
            }
            meta.getPersistentDataContainer().set(keys.item, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** The enchantment shimmer (1.20.5 and later). A server or test server without it just shows no shimmer. */
    private static void shimmer(ItemMeta meta) {
        try {
            meta.setEnchantmentGlintOverride(true);
        } catch (RuntimeException | LinkageError unsupported) {
            // not available here: the item works the same without the shimmer
        }
    }

    boolean isChunkHopper(ItemStack stack) {
        return stack != null && stack.getType() == Material.HOPPER && marked(stack, keys);
    }

    boolean isGhost(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(keys.ghost, PersistentDataType.BYTE);
    }

    private static boolean marked(ItemStack stack, Keys keys) {
        if (!stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(keys.item, PersistentDataType.BYTE);
    }

    /** A menu icon: one item of {@code material}, the ghost key, and a name (null keeps the game's own name) and lore. */
    @SuppressWarnings("deprecation") // legacy String names: the Adventure methods do not exist on Spigot
    ItemStack icon(Material material, String displayName, List<String> iconLore) {
        ItemStack stack = new ItemStack(material, 1);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            if (displayName != null) {
                meta.setDisplayName(displayName);
            }
            if (iconLore != null && !iconLore.isEmpty()) {
                meta.setLore(iconLore);
            }
            meta.getPersistentDataContainer().set(keys.ghost, PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }
}

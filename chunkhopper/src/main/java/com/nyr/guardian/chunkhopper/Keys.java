package com.nyr.guardian.chunkhopper;

import org.bukkit.NamespacedKey;

/**
 * Every persistent-data key the plugin writes, all in the fixed namespace {@code nyrchunkhopper} so a renamed jar or plugin
 * folder still finds its hoppers and items.
 */
final class Keys {

    static final String NAMESPACE = "nyrchunkhopper";

    /** BYTE 1 on a Chunk Hopper item. */
    final NamespacedKey item = key("item");
    /** BYTE 1 on every icon of the filter menu, so an icon that ever reached a player can be found and removed. */
    final NamespacedKey ghost = key("ghost");
    /** INTEGER format version on a placed Chunk Hopper's TileState: its presence is what makes a hopper a Chunk Hopper. */
    final NamespacedKey hopper = key("hopper");
    /** STRING a UUID for this placed Chunk Hopper, so its recorded sales never apply to another one at the same spot. */
    final NamespacedKey id = key("id");
    /** LONG the number of its last sale, raised in the same write that takes the sold stacks out. */
    final NamespacedKey saleSeq = key("sale-seq");
    /** STRING owner UUID. */
    final NamespacedKey owner = key("owner");
    /** STRING owner name when placed, for lists and menus while the owner is offline. */
    final NamespacedKey ownerName = key("owner-name");
    /** STRING nine material keys joined by commas; an empty field is a free filter slot. */
    final NamespacedKey filter = key("filter");
    /** BYTE 1 when the hopper sells what it holds. */
    final NamespacedKey autoSell = key("auto-sell");
    /** LONG items collected. */
    final NamespacedKey collected = key("collected");
    /** DOUBLE money earned. */
    final NamespacedKey earned = key("earned");
    /** INTEGER_ARRAY {x, y, z} on the chunk: where its Chunk Hopper stands, so it is found on load without a scan. */
    final NamespacedKey position = key("position");
    /** The crafting recipe. */
    final NamespacedKey recipe = key("chunk_hopper");

    private static NamespacedKey key(String name) {
        return new NamespacedKey(NAMESPACE, name);
    }
}

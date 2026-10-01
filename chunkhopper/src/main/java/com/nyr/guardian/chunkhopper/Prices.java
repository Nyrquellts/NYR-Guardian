package com.nyr.guardian.chunkhopper;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/**
 * The price of one item: EssentialsX's worth when it is installed and prices the item (price-source auto), otherwise the
 * prices section of config.yml. Items carrying other items (a filled shulker box, a bundle) are never sold.
 */
final class Prices {

    enum Source {
        AUTO, PRICES_ONLY;

        static Source parse(String text) {
            if (text != null && text.trim().toLowerCase(Locale.ROOT).replace('_', '-').equals("prices-only")) {
                return PRICES_ONLY;
            }
            return AUTO;
        }
    }

    private final Map<Material, BigDecimal> table;
    private final Source source;
    private final EssentialsWorth essentials;
    private final List<String> unknown;

    Prices(Map<Material, BigDecimal> table, Source source, EssentialsWorth essentials, List<String> unknown) {
        this.table = Map.copyOf(table);
        this.source = source;
        this.essentials = source == Source.AUTO ? essentials : null;
        this.unknown = List.copyOf(unknown);
    }

    /** Reads the prices section: material id to price; ids this server does not know are listed in {@link #unknown()}. */
    static Prices from(ConfigurationSection section, Source source, EssentialsWorth essentials) {
        Map<Material, BigDecimal> table = new HashMap<>();
        List<String> unknown = new ArrayList<>();
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Material material = Material.matchMaterial(key);
                if (material == null || material.isAir() || !material.isItem()) {
                    unknown.add(key);
                    continue;
                }
                double price = section.getDouble(key, 0);
                if (price > 0 && Double.isFinite(price)) {
                    table.put(material, BigDecimal.valueOf(price));
                }
            }
        }
        return new Prices(table, source, essentials, unknown);
    }

    /** The price of one of this item, or null when it is not for sale. */
    BigDecimal unit(ItemStack stack) {
        if (stack == null || stack.getType().isAir() || carriesItems(stack)) {
            return null;
        }
        if (essentials != null) {
            BigDecimal worth = essentials.price(stack);
            if (worth != null && worth.signum() > 0) {
                return worth;
            }
        }
        BigDecimal price = table.get(stack.getType());
        return price != null && price.signum() > 0 ? price : null;
    }

    private static boolean carriesItems(ItemStack stack) {
        if (!stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta instanceof BundleMeta bundle && bundle.hasItems()) {
            return true;
        }
        if (meta instanceof BlockStateMeta blockMeta && blockMeta.hasBlockState() && blockMeta.getBlockState() instanceof Container container) {
            return !container.getInventory().isEmpty();
        }
        return false;
    }

    List<String> unknown() {
        return unknown;
    }

    int size() {
        return table.size();
    }

    String describe() {
        if (source == Source.PRICES_ONLY) {
            return "config.yml only (" + table.size() + " prices)";
        }
        return (essentials != null ? "EssentialsX worth, then config.yml" : "config.yml (no EssentialsX)") + " (" + table.size() + " prices)";
    }

    /** EssentialsX's worth.yml, read by reflection so the plugin needs no EssentialsX to compile or run. */
    static final class EssentialsWorth {

        private final Plugin essentials;
        private final Object worth;
        private final Method price;
        private final Logger logger;
        private volatile boolean warned;

        private EssentialsWorth(Plugin essentials, Object worth, Method price, Logger logger) {
            this.essentials = essentials;
            this.worth = worth;
            this.price = price;
            this.logger = logger;
        }

        /** EssentialsX's worth when it is installed and enabled, otherwise null. */
        static EssentialsWorth find(Plugin essentials, Logger logger) {
            if (essentials == null || !essentials.isEnabled()) {
                return null;
            }
            try {
                Object worth = essentials.getClass().getMethod("getWorth").invoke(essentials);
                if (worth == null) {
                    return null;
                }
                Class<?> api = Class.forName("com.earth2me.essentials.IEssentials", false, essentials.getClass().getClassLoader());
                Method price = worth.getClass().getMethod("getPrice", api, ItemStack.class);
                return new EssentialsWorth(essentials, worth, price, logger);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException unknown) {
                logger.warning("EssentialsX is installed but its worth.yml could not be read (" + unknown + "); using the prices in config.yml.");
                return null;
            }
        }

        BigDecimal price(ItemStack stack) {
            try {
                Object value = price.invoke(worth, essentials, stack);
                return value instanceof BigDecimal decimal ? decimal : null;
            } catch (ReflectiveOperationException | RuntimeException failed) {
                if (!warned) {
                    warned = true;
                    logger.warning("EssentialsX could not price " + stack.getType() + " (" + failed + "); using config.yml prices where it fails.");
                }
                return null;
            }
        }
    }
}

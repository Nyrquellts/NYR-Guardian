package com.nyr.guardian.smarttick;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.MerchantRecipe;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Restocks a sleeping villager's trades, which its sleeping brain cannot do: at most twice a Minecraft day, during work
 * hours, while it stands within reach of its job site, as the game itself allows.
 *
 * <p>Where the server has {@code Villager#restock()} (Paper 1.21.11 and newer) that runs the game's own restock. Elsewhere
 * each trade is restocked the way the game does it: demand updated from the uses, uses set to 0, and the villager's restock
 * count raised where the API has one (Paper 1.20.6 and newer, not Spigot).
 */
final class Restocker {

    /** The villager work schedule: work from time 2000 to 9000 of each day. */
    static final long WORK_START = 2000;
    static final long WORK_END = 9000;
    /** The game restocks a villager working within this distance of its job site's centre. */
    static final double REACH = 1.73;
    private static final long DAY = 24_000;
    /** The game waits this long between a villager's first and second restock of a day. */
    private static final long SECOND_RESTOCK_GAP = 2400;
    /** And forgets the day's restocks after this long without one. */
    private static final long RESET_AFTER = 12_000;

    private final MethodHandle restock;
    private final MethodHandle getRestocksToday;
    private final MethodHandle setRestocksToday;

    /** {@code villagerApi} is {@code Villager.class}, whose methods on this server decide which way restocks go. */
    Restocker(Class<?> villagerApi) {
        restock = find(villagerApi, "restock", MethodType.methodType(void.class));
        getRestocksToday = find(villagerApi, "getRestocksToday", MethodType.methodType(int.class));
        setRestocksToday = find(villagerApi, "setRestocksToday", MethodType.methodType(void.class, int.class));
    }

    private static MethodHandle find(Class<?> owner, String name, MethodType type) {
        try {
            return MethodHandles.publicLookup().findVirtual(owner, name, type);
        } catch (NoSuchMethodException | IllegalAccessException absent) {
            return null;
        }
    }

    /** True when the server's own restock runs; false when trades are restocked one by one. */
    boolean serverRestock() {
        return restock != null;
    }

    /** Restocks the villager when the game would; true when it did. Run on the villager's own thread. */
    boolean maybeRestock(Villager villager, Location jobSite) {
        World world = villager.getWorld();
        long time = world.getFullTime();
        long timeOfDay = Math.floorMod(time, DAY);
        if (timeOfDay < WORK_START || timeOfDay >= WORK_END || jobSite == null || !world.equals(jobSite.getWorld())) {
            return false;
        }
        Location at = villager.getLocation();
        double dx = at.getX() - (jobSite.getBlockX() + 0.5);
        double dy = at.getY() - (jobSite.getBlockY() + 0.5);
        double dz = at.getZ() - (jobSite.getBlockZ() + 0.5);
        if (dx * dx + dy * dy + dz * dz >= REACH * REACH) {
            return false;
        }
        List<MerchantRecipe> recipes = villager.getRecipes();
        boolean used = false;
        for (MerchantRecipe recipe : recipes) {
            if (recipe.getUses() > 0) {
                used = true;
                break;
            }
        }
        if (!used) {
            return false;
        }
        PersistentDataContainer data = villager.getPersistentDataContainer();
        long day = Math.floorDiv(time, DAY);
        long today = 0;
        long last = 0;
        long[] record = data.get(Marks.RESTOCKS, PersistentDataType.LONG_ARRAY);
        if (record != null && record.length == 3 && record[0] == day && time >= record[2] && time - record[2] <= RESET_AFTER) {
            today = record[1];
            last = record[2];
        }
        if (today >= 2 || (today == 1 && time <= last + SECOND_RESTOCK_GAP)) {
            return false;
        }
        restock(villager, recipes);
        data.set(Marks.RESTOCKS, PersistentDataType.LONG_ARRAY, new long[] {day, today + 1, time});
        return true;
    }

    private void restock(Villager villager, List<MerchantRecipe> recipes) {
        if (restock != null) {
            try {
                restock.invoke(villager);
                return;
            } catch (Throwable refused) {
                // restocked below, trade by trade
            }
        }
        for (MerchantRecipe recipe : recipes) {
            recipe.setDemand(recipe.getDemand() + recipe.getUses() - (recipe.getMaxUses() - recipe.getUses()));
            recipe.setUses(0);
        }
        if (getRestocksToday != null && setRestocksToday != null) {
            try {
                int count = (int) getRestocksToday.invoke(villager);
                setRestocksToday.invoke(villager, count + 1);
            } catch (Throwable refused) {
                // the count is the game's own bookkeeping; the trades are restocked either way
            }
        }
    }
}

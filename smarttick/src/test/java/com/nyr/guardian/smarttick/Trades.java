package com.nyr.guardian.smarttick;

import java.lang.reflect.Proxy;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Villager;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.Merchant;
import org.bukkit.inventory.MerchantInventory;

/**
 * The events a server fires when a player opens and closes a villager's trades. MockBukkit cannot open a merchant window, so
 * the trade window is a stand-in that answers what listeners ask: its merchant and its player.
 */
public final class Trades {

    private Trades() {
    }

    public static InventoryOpenEvent open(HumanEntity player, Villager villager) {
        return new InventoryOpenEvent(view(player, villager));
    }

    public static InventoryCloseEvent close(HumanEntity player, Villager villager) {
        return new InventoryCloseEvent(view(player, villager));
    }

    /**
     * As Paper 1.20.6 opens a villager's trades: the window's merchant is a separate wrapper, not the villager, and the
     * villager is only known by trading with the player.
     */
    public static InventoryOpenEvent openThroughWrapper(HumanEntity player) {
        Merchant wrapper = (Merchant) Proxy.newProxyInstance(Trades.class.getClassLoader(), new Class<?>[] {Merchant.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getTrader" -> player;
                case "isTrading" -> true;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "a merchant wrapper";
                default -> throw new UnsupportedOperationException("not in this test: " + method.getName());
            });
        return new InventoryOpenEvent(view(player, wrapper));
    }

    private static InventoryView view(HumanEntity player, Merchant merchant) {
        MerchantInventory trades = (MerchantInventory) Proxy.newProxyInstance(Trades.class.getClassLoader(),
            new Class<?>[] {MerchantInventory.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getMerchant" -> merchant;
                case "getHolder" -> merchant instanceof org.bukkit.inventory.InventoryHolder holder ? holder : null;
                case "getViewers" -> java.util.List.of(player);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "trades of " + merchant;
                default -> throw new UnsupportedOperationException("not in this test: " + method.getName());
            });
        return (InventoryView) Proxy.newProxyInstance(Trades.class.getClassLoader(), new Class<?>[] {InventoryView.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getTopInventory" -> trades;
                case "getPlayer" -> player;
                case "getTitle", "getOriginalTitle" -> "Trades";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "trade window";
                default -> throw new UnsupportedOperationException("not in this test: " + method.getName());
            });
    }
}

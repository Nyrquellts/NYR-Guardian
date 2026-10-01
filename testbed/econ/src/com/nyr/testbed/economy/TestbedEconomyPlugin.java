package com.nyr.testbed.economy;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * A Vault economy for testbed servers that have no Folia-capable economy plugin. It keeps balances in memory and answers the
 * two commands the test bots use, in EssentialsX's words: {@code /balance} and {@code /eco set|give|take <player> <amount>}.
 */
public final class TestbedEconomyPlugin extends JavaPlugin {

    private final TestbedEconomy economy = new TestbedEconomy();

    @Override
    public void onEnable() {
        getServer().getServicesManager().register(Economy.class, economy, this, ServicePriority.Highest);
        getLogger().info("registered the testbed Vault economy");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "balance" -> {
                if (!(sender instanceof Player player)) {
                    return false;
                }
                sender.sendMessage("Balance: $" + String.format(Locale.ROOT, "%,.2f", economy.cents(player.getUniqueId()) / 100.0));
                return true;
            }
            case "eco" -> {
                if (args.length != 3) {
                    return false;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage("Player not found.");
                    return true;
                }
                long cents = new BigDecimal(args[2]).movePointRight(2).longValueExact();
                long current = economy.cents(target.getUniqueId());
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "set" -> economy.set(target.getUniqueId(), cents);
                    case "give" -> economy.set(target.getUniqueId(), current + cents);
                    case "take" -> economy.set(target.getUniqueId(), current - cents);
                    default -> {
                        return false;
                    }
                }
                sender.sendMessage("Balance of " + target.getName() + " is now $" + String.format(Locale.ROOT, "%,.2f", economy.cents(target.getUniqueId()) / 100.0));
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}

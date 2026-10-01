package com.nyr.guardian.chunkhopper;

import java.util.Locale;
import java.util.UUID;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Pays through Vault's economy service. Created only after {@link Payout#find} saw Vault's classes, so a server without
 * Vault never loads this class. The provider is looked up at every sale: economies may register after this plugin.
 */
final class VaultPayout implements Payout {

    private final Server server;

    VaultPayout(Server server) {
        this.server = server;
    }

    private Economy economy() {
        RegisteredServiceProvider<Economy> registration = server.getServicesManager().getRegistration(Economy.class);
        return registration == null ? null : registration.getProvider();
    }

    @Override
    public boolean ready() {
        Economy economy = economy();
        return economy != null && economy.isEnabled();
    }

    @Override
    public String name() {
        Economy economy = economy();
        return economy == null ? "Vault (no economy plugin)" : "Vault (" + economy.getName() + ")";
    }

    @Override
    public String format(double amount) {
        Economy economy = economy();
        try {
            if (economy != null) {
                return economy.format(amount);
            }
        } catch (RuntimeException ignored) {
            // an economy that cannot format still pays
        }
        return String.format(Locale.ROOT, "%.2f", amount);
    }

    @Override
    public String deposit(UUID player, double amount) {
        Economy economy = economy();
        if (economy == null || !economy.isEnabled()) {
            return "no economy";
        }
        OfflinePlayer owner = server.getOfflinePlayer(player);
        EconomyResponse response;
        try {
            response = economy.depositPlayer(owner, amount);
        } catch (RuntimeException failed) {
            return failed.getClass().getSimpleName() + (failed.getMessage() == null ? "" : ": " + failed.getMessage());
        }
        if (response == null) {
            return "no answer";
        }
        if (!response.transactionSuccess()) {
            return response.errorMessage == null || response.errorMessage.isBlank() ? "refused" : response.errorMessage;
        }
        return null;
    }
}

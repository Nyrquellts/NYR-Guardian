package com.nyr.guardian.chunkhopper;

import java.util.Locale;
import java.util.UUID;
import org.bukkit.Server;

/** Where sale money goes: the server's Vault economy, or nowhere when there is none. */
interface Payout {

    /** Whether an economy is there to pay into right now. */
    boolean ready();

    /** The economy's name for /chunkhopper status. */
    String name();

    /** The amount as the economy writes money. */
    String format(double amount);

    /**
     * Pays {@code amount} to the player (who may be offline).
     *
     * @return null when paid, otherwise why not
     */
    String deposit(UUID player, double amount);

    /** Vault's economy when Vault's API is on the server, otherwise {@link #NONE}. This interface names no Vault type. */
    static Payout find(Server server, ClassLoader loader) {
        try {
            Class.forName("net.milkbowl.vault.economy.Economy", false, loader);
        } catch (ClassNotFoundException | LinkageError absent) {
            return NONE;
        }
        return new VaultPayout(server);
    }

    Payout NONE = new Payout() {
        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public String name() {
            return "none (no Vault)";
        }

        @Override
        public String format(double amount) {
            return String.format(Locale.ROOT, "%.2f", amount);
        }

        @Override
        public String deposit(UUID player, double amount) {
            return "no economy";
        }
    };
}

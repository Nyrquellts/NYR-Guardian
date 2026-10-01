package com.nyr.guardian.chunkhopper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

/**
 * A Vault economy in memory, in cents, for the tests: it counts every deposit and can be told to refuse them, so a sale that
 * pays twice, or pays without taking the items, shows up. Only the {@link OfflinePlayer} methods are used by the plugin; the
 * name-based ones fail the test.
 */
public final class TestEconomy implements Economy {

    private final Map<UUID, AtomicLong> cents = new ConcurrentHashMap<>();
    private final LongAdder deposits = new LongAdder();
    private final LongAdder refused = new LongAdder();
    private volatile boolean refuse;
    private volatile Runnable onDeposit;

    private static long toCents(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
    }

    public long cents(UUID player) {
        AtomicLong account = cents.get(player);
        return account == null ? 0 : account.get();
    }

    public long deposits() {
        return deposits.sum();
    }

    public long refused() {
        return refused.sum();
    }

    /** From now on every deposit fails (true) or succeeds (false). */
    public void refuse(boolean on) {
        this.refuse = on;
    }

    /** Runs inside every deposit, before the money moves: a test looks at the world at the moment it is paid. */
    public void onDeposit(Runnable check) {
        this.onDeposit = check;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "TestEconomy";
    }

    @Override
    public boolean hasBankSupport() {
        return false;
    }

    @Override
    public int fractionalDigits() {
        return 2;
    }

    @Override
    public String format(double amount) {
        return String.format(Locale.ROOT, "$%.2f", amount);
    }

    @Override
    public String currencyNamePlural() {
        return "dollars";
    }

    @Override
    public String currencyNameSingular() {
        return "dollar";
    }

    @Override
    public boolean hasAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean hasAccount(OfflinePlayer player, String world) {
        return true;
    }

    @Override
    public double getBalance(OfflinePlayer player) {
        return cents(player.getUniqueId()) / 100.0;
    }

    @Override
    public double getBalance(OfflinePlayer player, String world) {
        return getBalance(player);
    }

    @Override
    public boolean has(OfflinePlayer player, double amount) {
        return cents(player.getUniqueId()) >= toCents(amount);
    }

    @Override
    public boolean has(OfflinePlayer player, String world, double amount) {
        return has(player, amount);
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, double amount) {
        throw new AssertionError("NYR ChunkHopper never withdraws");
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        throw new AssertionError("NYR ChunkHopper never withdraws");
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        if (amount <= 0) {
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "not a deposit");
        }
        Runnable check = onDeposit;
        if (check != null) {
            check.run();
        }
        if (refuse) {
            refused.increment();
            return new EconomyResponse(0, getBalance(player), EconomyResponse.ResponseType.FAILURE, "the test economy refuses");
        }
        cents.computeIfAbsent(player.getUniqueId(), id -> new AtomicLong()).addAndGet(toCents(amount));
        deposits.increment();
        return new EconomyResponse(amount, getBalance(player), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        return true;
    }

    private static AssertionError byName() {
        return new AssertionError("name-based economy calls are not used by NYR ChunkHopper");
    }

    @Override
    public boolean hasAccount(String playerName) {
        throw byName();
    }

    @Override
    public boolean hasAccount(String playerName, String worldName) {
        throw byName();
    }

    @Override
    public double getBalance(String playerName) {
        throw byName();
    }

    @Override
    public double getBalance(String playerName, String world) {
        throw byName();
    }

    @Override
    public boolean has(String playerName, double amount) {
        throw byName();
    }

    @Override
    public boolean has(String playerName, String worldName, double amount) {
        throw byName();
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        throw byName();
    }

    @Override
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        throw byName();
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, double amount) {
        throw byName();
    }

    @Override
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        throw byName();
    }

    @Override
    public boolean createPlayerAccount(String playerName) {
        throw byName();
    }

    @Override
    public boolean createPlayerAccount(String playerName, String worldName) {
        throw byName();
    }

    @Override
    public EconomyResponse createBank(String name, String player) {
        throw byName();
    }

    @Override
    public EconomyResponse createBank(String name, OfflinePlayer player) {
        throw byName();
    }

    @Override
    public EconomyResponse deleteBank(String name) {
        throw byName();
    }

    @Override
    public EconomyResponse bankBalance(String name) {
        throw byName();
    }

    @Override
    public EconomyResponse bankHas(String name, double amount) {
        throw byName();
    }

    @Override
    public EconomyResponse bankWithdraw(String name, double amount) {
        throw byName();
    }

    @Override
    public EconomyResponse bankDeposit(String name, double amount) {
        throw byName();
    }

    @Override
    public EconomyResponse isBankOwner(String name, String playerName) {
        throw byName();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        throw byName();
    }

    @Override
    public EconomyResponse isBankMember(String name, String playerName) {
        throw byName();
    }

    @Override
    public EconomyResponse isBankMember(String name, OfflinePlayer player) {
        throw byName();
    }

    @Override
    public List<String> getBanks() {
        return List.of();
    }
}

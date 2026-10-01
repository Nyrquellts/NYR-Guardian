package com.nyr.testbed.economy;

import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.OfflinePlayer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * A server economy for live tests, as another plugin would provide it: every call takes effect the moment it
 * returns. Balances are cents. Every withdrawal and deposit is counted per player, so a double deduction shows up as a count,
 * not only as a balance. Only the {@link OfflinePlayer} methods are implemented; a name-based call fails the test.
 */
public final class TestbedEconomy implements Economy {

    private final Map<UUID, AtomicLong> cents = new ConcurrentHashMap<>();
    private final Map<UUID, LongAdder> withdrawals = new ConcurrentHashMap<>();
    private final Map<UUID, LongAdder> deposits = new ConcurrentHashMap<>();
    private final LongAdder refused = new LongAdder();

    private static long toCents(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_EVEN).movePointRight(2).longValueExact();
    }

    private static double toDouble(long c) {
        return BigDecimal.valueOf(c).movePointLeft(2).doubleValue();
    }

    private AtomicLong account(UUID id) {
        return cents.computeIfAbsent(id, k -> new AtomicLong());
    }

    public long cents(UUID player) {
        return account(player).get();
    }

    public void set(UUID player, long value) {
        account(player).set(value);
    }

    public long withdrawals(UUID player) {
        LongAdder n = withdrawals.get(player);
        return n == null ? 0L : n.sum();
    }

    public long deposits(UUID player) {
        LongAdder n = deposits.get(player);
        return n == null ? 0L : n.sum();
    }

    public long refusedWithdrawals() {
        return refused.sum();
    }

    public long total() {
        long sum = 0;
        for (AtomicLong v : cents.values()) {
            sum += v.get();
        }
        return sum;
    }

    // ------------------------------------------------------------------ Economy, by player

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String getName() {
        return "NyrTestbedEconomy";
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
        return "$" + BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_EVEN).toPlainString();
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
        return toDouble(cents(player.getUniqueId()));
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
        long c = toCents(amount);
        if (c <= 0) {
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, "not positive");
        }
        AtomicLong balance = account(player.getUniqueId());
        while (true) {
            long now = balance.get();
            if (now < c) {
                refused.increment();
                return new EconomyResponse(amount, toDouble(now), EconomyResponse.ResponseType.FAILURE, "insufficient funds");
            }
            if (balance.compareAndSet(now, now - c)) {
                withdrawals.computeIfAbsent(player.getUniqueId(), k -> new LongAdder()).increment();
                return new EconomyResponse(amount, toDouble(now - c), EconomyResponse.ResponseType.SUCCESS, null);
            }
        }
    }

    @Override
    public EconomyResponse withdrawPlayer(OfflinePlayer player, String world, double amount) {
        return withdrawPlayer(player, amount);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, double amount) {
        long c = toCents(amount);
        if (c <= 0) {
            return new EconomyResponse(amount, 0, EconomyResponse.ResponseType.FAILURE, "not positive");
        }
        long after = account(player.getUniqueId()).addAndGet(c);
        deposits.computeIfAbsent(player.getUniqueId(), k -> new LongAdder()).increment();
        return new EconomyResponse(amount, toDouble(after), EconomyResponse.ResponseType.SUCCESS, null);
    }

    @Override
    public EconomyResponse depositPlayer(OfflinePlayer player, String world, double amount) {
        return depositPlayer(player, amount);
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player) {
        account(player.getUniqueId());
        return true;
    }

    @Override
    public boolean createPlayerAccount(OfflinePlayer player, String world) {
        return createPlayerAccount(player);
    }

    // ------------------------------------------------------------------ by name and banks: never used by the tests

    private static UnsupportedOperationException byName() {
        return new UnsupportedOperationException("the tests address the economy by player, never by name");
    }

    @Override
    @Deprecated
    public boolean hasAccount(String playerName) {
        throw byName();
    }

    @Override
    @Deprecated
    public boolean hasAccount(String playerName, String worldName) {
        throw byName();
    }

    @Override
    @Deprecated
    public double getBalance(String playerName) {
        throw byName();
    }

    @Override
    @Deprecated
    public double getBalance(String playerName, String world) {
        throw byName();
    }

    @Override
    @Deprecated
    public boolean has(String playerName, double amount) {
        throw byName();
    }

    @Override
    @Deprecated
    public boolean has(String playerName, String worldName, double amount) {
        throw byName();
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String playerName, double amount) {
        throw byName();
    }

    @Override
    @Deprecated
    public EconomyResponse withdrawPlayer(String playerName, String worldName, double amount) {
        throw byName();
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String playerName, double amount) {
        throw byName();
    }

    @Override
    @Deprecated
    public EconomyResponse depositPlayer(String playerName, String worldName, double amount) {
        throw byName();
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String playerName) {
        throw byName();
    }

    @Override
    @Deprecated
    public boolean createPlayerAccount(String playerName, String worldName) {
        throw byName();
    }

    @Override
    @Deprecated
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
    @Deprecated
    public EconomyResponse isBankOwner(String name, String playerName) {
        throw byName();
    }

    @Override
    public EconomyResponse isBankOwner(String name, OfflinePlayer player) {
        throw byName();
    }

    @Override
    @Deprecated
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

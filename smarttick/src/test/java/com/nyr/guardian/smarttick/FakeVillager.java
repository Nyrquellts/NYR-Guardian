package com.nyr.guardian.smarttick;

import be.seeseemelk.mockbukkit.ServerMock;
import be.seeseemelk.mockbukkit.entity.AgeableMock;
import com.destroystokyo.paper.entity.villager.Reputation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.ZombieVillager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.MerchantRecipe;

/**
 * A villager for MockBukkit, which has none: profession, brain memories (job site, bed), trades, a trading player and a bed
 * pose, as the plugin reads them through the Bukkit API. Its {@link #restock()} is the game's, for servers that have it.
 */
public class FakeVillager extends AgeableMock implements RestockingVillager {

    private Villager.Profession profession = Villager.Profession.NONE;
    private Villager.Type villagerType;
    private final Map<MemoryKey<?>, Object> memories = new HashMap<>();
    private final List<MerchantRecipe> recipes = new ArrayList<>();
    private HumanEntity trader;
    private boolean inBed;
    private boolean leashed;
    private int restocksToday;
    private int level = 1;
    private int experience;
    private boolean ageLock;
    private boolean breed = true;
    /** How often the server's own restock ran. */
    public int serverRestocks;

    public FakeVillager(ServerMock server, UUID uuid) {
        super(server, uuid);
    }

    /** A villager standing at {@code at}, registered with the server so worlds list it. */
    public static FakeVillager spawn(ServerMock server, Location at) {
        FakeVillager villager = new FakeVillager(server, UUID.randomUUID());
        villager.setLocation(at);
        server.registerEntity(villager);
        return villager;
    }

    @Override
    public EntityType getType() {
        return EntityType.VILLAGER;
    }

    @Override
    public Villager.Profession getProfession() {
        return profession;
    }

    @Override
    public void setProfession(Villager.Profession profession) {
        this.profession = profession;
    }

    @Override
    public Villager.Type getVillagerType() {
        return villagerType;
    }

    @Override
    public void setVillagerType(Villager.Type type) {
        this.villagerType = type;
    }

    @Override
    public int getVillagerLevel() {
        return level;
    }

    @Override
    public void setVillagerLevel(int level) {
        this.level = level;
    }

    @Override
    public int getVillagerExperience() {
        return experience;
    }

    @Override
    public void setVillagerExperience(int experience) {
        this.experience = experience;
    }

    @Override
    public boolean increaseLevel(int amount) {
        level += amount;
        return true;
    }

    @Override
    public boolean addTrades(int amount) {
        return false;
    }

    @Override
    public int getRestocksToday() {
        return restocksToday;
    }

    @Override
    public void setRestocksToday(int restocksToday) {
        this.restocksToday = restocksToday;
    }

    /** The game's restock: demand from the uses, uses back to 0, one more restock today. */
    @Override
    public void restock() {
        serverRestocks++;
        for (MerchantRecipe recipe : recipes) {
            recipe.setDemand(recipe.getDemand() + recipe.getUses() - (recipe.getMaxUses() - recipe.getUses()));
            recipe.setUses(0);
        }
        restocksToday++;
    }

    @Override
    public boolean sleep(Location location) {
        inBed = true;
        return true;
    }

    @Override
    public void wakeup() {
        inBed = false;
    }

    @Override
    public boolean isSleeping() {
        return inBed;
    }

    @Override
    public void shakeHead() {
    }

    @Override
    public ZombieVillager zombify() {
        throw new UnsupportedOperationException("not in this test");
    }

    @Override
    public Reputation getReputation(UUID player) {
        return new Reputation();
    }

    @Override
    public Map<UUID, Reputation> getReputations() {
        return Map.of();
    }

    @Override
    public void setReputation(UUID player, Reputation reputation) {
    }

    @Override
    public void setReputations(Map<UUID, Reputation> reputations) {
    }

    @Override
    public void clearReputations() {
    }

    @Override
    public Inventory getInventory() {
        throw new UnsupportedOperationException("not in this test");
    }

    @Override
    public void resetOffers() {
        recipes.clear();
    }

    @Override
    public List<MerchantRecipe> getRecipes() {
        return recipes;
    }

    @Override
    public void setRecipes(List<MerchantRecipe> recipes) {
        this.recipes.clear();
        this.recipes.addAll(recipes);
    }

    @Override
    public MerchantRecipe getRecipe(int i) {
        return recipes.get(i);
    }

    @Override
    public void setRecipe(int i, MerchantRecipe recipe) {
        recipes.set(i, recipe);
    }

    @Override
    public int getRecipeCount() {
        return recipes.size();
    }

    @Override
    public boolean isTrading() {
        return trader != null;
    }

    @Override
    public HumanEntity getTrader() {
        return trader;
    }

    public void setTrader(HumanEntity trader) {
        this.trader = trader;
    }

    @Override
    public void setAgeLock(boolean lock) {
        this.ageLock = lock;
    }

    @Override
    public boolean getAgeLock() {
        return ageLock;
    }

    @Override
    public boolean canBreed() {
        return breed;
    }

    @Override
    public void setBreed(boolean breed) {
        this.breed = breed;
    }

    @Override
    public boolean isLeashed() {
        return leashed;
    }

    public void setLeashedForTest(boolean leashed) {
        this.leashed = leashed;
    }

    @Override
    public <T> T getMemory(MemoryKey<T> key) {
        return key.getMemoryClass().cast(memories.get(key));
    }

    @Override
    public <T> void setMemory(MemoryKey<T> key, T value) {
        if (value == null) {
            memories.remove(key);
        } else {
            memories.put(key, value);
        }
    }
}

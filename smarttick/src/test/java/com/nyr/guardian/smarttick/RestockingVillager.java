package com.nyr.guardian.smarttick;

import org.bukkit.entity.Villager;

/** The villager API of servers that have {@code Villager#restock()} (Paper 1.21.11 and newer), which the compile API lacks. */
public interface RestockingVillager extends Villager {

    void restock();
}

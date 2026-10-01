NYR SmartTick @version@
===================

When the server falls behind, SmartTick puts trading-hall villagers' brains to sleep: they stop pathfinding, gossiping,
looking for beds and work, the thinking every villager does every tick, while players keep trading with them. When the
server catches up, they wake. It uses Bukkit's own switch for this (Mob#setAware), so a sleeping villager is a normal
villager with its brain paused, and nothing is ever removed, killed or despawned.

Only villagers that gain nothing from thinking sleep: a villager with a profession and a job site next to it, no bed,
that cannot go anywhere (walled into a cell, sitting in a minecart or boat, or standing still for 5 minutes). Iron farms,
breeders and villages keep their brains.


In this download
----------------
  NYR-SmartTick-@version@.jar
      the plugin
  defaults/config.yml
      the settings the plugin writes on its first start, for reference
  THIRD-PARTY-NOTICES.txt
      the one open-source library inside the jar (FoliaLib, MIT) and its licence


Requirements
------------
  - Java 21 or newer (Minecraft 26 servers need the Java they ship for).
  - A Paper, Purpur, Folia or Spigot server, Minecraft 1.20.6 or newer. No other plugin is needed.

  Tested live on Paper 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.11 and 26.1.2, Purpur 1.21.11, Folia 1.21.11
  and Spigot 1.21.11: villagers sleeping and waking through each server type's own load reading, players trading with
  sleeping villagers, restocks while asleep, chunks unloading, clean restarts, hard kills and the plugin being removed.
  On Paper 26.2 it was tested to start and load cleanly.


Installing
----------
  1. Put NYR-SmartTick-@version@.jar in the plugins folder and start (or restart) the server.
  2. The plugin creates plugins/NYR-SmartTick/config.yml. Adjust it if you like, then run /smarttick reload.
  Updating keeps your config.yml: settings added by a new version are written into it with their comments.


Removing the plugin
-------------------
  Before removing the plugin, run /smarttick wakeall and wait for "Every loaded villager is awake", then stop the server
  and remove the jar.

  Why: a sleeping villager's paused brain is saved with it. SmartTick wakes sleepers before their chunk unloads and when
  the server stops, so normally nobody is saved asleep; /smarttick wakeall before the last stop makes sure of it.

  After a crash, the last autosave may hold villagers asleep. Start the server with SmartTick still installed and visit
  your trading halls (their chunks must load): SmartTick wakes every villager saved asleep as its chunk loads, unless the
  server is still behind. Then run /smarttick wakeall and remove the plugin as above.


How SmartTick knows the server is behind
----------------------------------------
  It uses the best reading each server type offers; /smarttick shows which one it uses.

  Paper, Purpur   Milliseconds per tick (MSPT), the server's 5-second average. Villagers sleep at 40 ms or more and
                  wake at 35 ms or less. A tick may take 50 ms at most for 20 TPS, so SmartTick reacts before players
                  notice lag.
  Folia           Each region's own reading: a villager sleeps when the region it stands in is behind, however the rest
                  of the server is doing.
  Spigot          Spigot reports no tick time, so SmartTick counts ticks per second itself. Villagers sleep below
                  19.0 TPS and wake at 19.8 TPS or more: on Spigot it reacts once the server is below 20 TPS, on Paper
                  already at 40 ms per tick.

  Either way the reading must stay past its threshold for 5 seconds (hold-seconds) before anything changes, so one lag
  spike does nothing. At most 200 villagers fall asleep or wake per second (batch-per-second), so waking a big trading
  hall is not a lag spike itself.

  When the villagers themselves are what puts the server behind, the server only reads as recovered because they sleep,
  and waking them brings the lag straight back. SmartTick notices: if the server falls behind again within 5 minutes of
  them waking, the next sleep lasts at least 2 minutes, then 4, 8 and so on up to an hour, however good the reading
  looks meanwhile (/smarttick shows how long). A recovery that lasts 5 minutes, or a reload, starts over.


Villagers far from players, and spigot.yml
------------------------------------------
  Sleep pauses the brain of every villager the server ticks in full: the villagers within the entity activation range of
  a player (spigot.yml, world-settings: default: entity-activation-range; 32 blocks for villagers on Paper). Farther
  away, but still within simulation distance, the server ticks villagers as "inactive", and with
  tick-inactive-villagers: true (the default, in the same section) it runs their brains on those ticks itself, asleep or
  not. SmartTick cannot pause those ticks; it says so in the console at start while the setting is on.

  Set tick-inactive-villagers: false for sleep to pause every eligible villager fully. That setting also stops villagers
  far from players from working and restocking, whether SmartTick is installed or not.

  Spigot (not Paper, Purpur or Folia) also gives villagers next to a player one "inactive" tick in four. On Spigot,
  tick-inactive-villagers: false is what lets sleep pause even the villagers you stand next to.


What a sleeping villager still does
-----------------------------------
  - Trades. Opening its trades wakes it at once, and it stays awake for 10 seconds after the window closes, so it levels
    up after a trade as the game does. Then it sleeps again if the server is still behind.
  - Restocks. A sleeping brain cannot restock, so SmartTick does it the game's way: during work hours, while the villager
    stands next to its job site, at most twice a Minecraft day, only trades that were used. On Paper 1.21.11 and newer
    this runs the server's own restock; elsewhere each trade is restocked with the game's demand rule.
  - Notices its job site breaking: a player breaking it wakes the villager at once, so a villager you never traded with
    loses its profession as the game expects and can take a new job site (the usual way to reroll a librarian).


Which villagers never sleep
---------------------------
  Babies, nitwits, villagers without a profession or without a job site, villagers with a bed (skip-villagers-with-beds),
  villagers a player is trading with, leading on a lead or sharing a vehicle with, villagers sleeping in a bed, villagers
  whose AI another plugin switched off, other plugins' NPCs, villagers in worlds you switched off, and villagers that
  can walk away and have not stood still for 5 minutes.

  Villagers another plugin put to sleep are never touched: SmartTick marks the villagers it puts to sleep and only ever
  wakes those.

  /smarttick check lists the villagers around you with the reason each one sleeps or not.


Commands
--------
  /smarttick                status: the load reading, the thresholds, the mode, how many villagers are loaded,
                            eligible and asleep, and what SmartTick did since start
  /smarttick check [radius] every villager within the radius (8 blocks, at most 32) and why it sleeps or not
  /smarttick sleep          sleep every eligible villager now, whatever the load (for tests and demonstrations)
  /smarttick wakeall        wake every sleeping villager and keep them awake (run before removing the plugin)
  /smarttick auto           follow the server load again after sleep or wakeall
  /smarttick reload         reload config.yml
  Alias: /stick. sleep and wakeall last until /smarttick auto, a reload or a restart.


Permissions
-----------
  nyrsmarttick.admin    every /smarttick command                                     default: op
  nyrsmarttick.alerts   staff alerts when the server falls behind and when it recovers  default: op


Staff alerts
------------
  When the server falls behind and villagers go to sleep, and when it catches up and they wake, staff with
  nyrsmarttick.alerts are told, with the reading. The same line goes to the console and to
  plugins/NYR-SmartTick/alerts.log. The same alert at most once every five minutes (alerts.cooldown-seconds).


Performance
-----------
  Measured once on Paper 1.21.11 with Paper's own /mspt (1-minute averages), on a desktop (Intel Core Ultra 9 285) that
  ran other work at the same time. Farmers in 1x1 glass cells, each holding the composter in front of it, all within
  32 blocks of a player. An empty server read 0.6 ms per tick.

      villagers   awake (before / after sleeping)   asleep
         200         5.2 /   5.2 ms                   1.0 ms
         500        14.1 /  14.7 ms                   2.1 ms
        1000        33.5 /  34.3 ms                   3.8 ms
        2000       103.9 / 107.1 ms                   8.0 ms

  With the player 96 blocks from the same 2000 villagers and spigot.yml's tick-inactive-villagers at its default (true),
  they read 97.3 ms awake and 77.9 ms asleep: see "Villagers far from players" above.

  SmartTick itself looks at each loaded villager once per scan (scan-seconds, 5), a slice of them every tick rather than
  all at once. On Folia every villager is looked at on the thread of the region it stands in.


What SmartTick does not do
--------------------------
  It does not change hoppers, redstone, farms or any other entity, and it removes nothing. It cannot make a server fast
  whose lag comes from something other than villagers; /smarttick shows the reading, so you can see whether villagers
  sleeping made a difference.

NYR ChunkHopper @version@
======================

One Chunk Hopper per chunk collects the items that drop in that chunk the moment they drop, such as mob farm loot and
cactus breaking off a farm. A collected item never becomes an item on the ground, so drops cannot pile up or despawn. A Chunk Hopper is a real hopper and still feeds a chest below it. Its owner can give it a filter
and, with Vault and an economy plugin, have it sell what it collects into their balance.


In this download
----------------
  NYR-ChunkHopper-@version@.jar
      the plugin
  defaults/config.yml
      the settings the plugin writes on its first start, for reference
  THIRD-PARTY-NOTICES.txt
      the one open-source library inside the jar (FoliaLib, MIT) and its licence


Requirements
------------
  - Java 21 or newer (Minecraft 26 servers need the Java they ship for).
  - A Paper, Purpur, Folia or Spigot server.
  - To sell: Vault (on Folia, a Vault that supports Folia, such as VaultUnlocked) and an economy plugin. Without them
    Chunk Hoppers collect and filter; nothing is sold.

  Tested live on Paper 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.11 and 26.1.2, Purpur 1.21.11, Folia 1.21.11
  and Spigot 1.21.11, with test players: placing and the one-per-chunk rule, cow loot and cactus going straight into the
  hopper, thrown items, /give copies, death drops and a mined block staying on the ground, the filter menu (a barrage of
  every kind of click never handed out an icon), selling with Vault and EssentialsX (on Folia, VaultUnlocked and a test
  economy), another player unable to open or break it, breaking it with a pickaxe, TNT beside it, feeding a chest, the
  per-player limit, reloads and restarts, and a hard kill of the server right after a sale. On Paper 26.2 it was tested
  to start and load cleanly.


Installing
----------
  1. Put NYR-ChunkHopper-@version@.jar in the plugins folder and start (or restart) the server.
  2. Adjust plugins/NYR-ChunkHopper/config.yml if you like (prices, the recipe, a per-player limit), then run
     /chunkhopper reload.
  3. Give Chunk Hoppers with /chunkhopper give <player> [amount], or let players craft them (recipe in config.yml:
     iron blocks, hoppers and an ender pearl).


How it works
------------
  - Place a Chunk Hopper anywhere in a chunk. Only one fits in a chunk. Every item that drops in that chunk from then
    on goes straight into it. When it is full, drops lie on the ground as usual (with auto-sell on, it sells first).
  - Never collected: items a player throws or drops, what /give hands out, a player's death drops, a player's fishing
    catch, and the drops of blocks a player breaks by hand (collect.player-mined: true collects those too).
  - Filter: shift + right-click the Chunk Hopper with an empty hand. Click a filter slot with an item on your cursor to
    collect that item, click it with an empty hand to free it, or shift-click an item in your own inventory to add it.
    Nothing moves: the menu only shows icons. An empty filter collects everything. The menu also shows the owner, what
    it collected and earned, the auto-sell switch and a button that clears the filter.
  - Selling: every auto-sell.interval-seconds (10) each Chunk Hopper sells the stacks it holds that have a price. The
    items leave the hopper first and the owner is paid second; if the economy refuses the payment, the items go back.
    Prices come from EssentialsX's worth.yml when EssentialsX prices the item, otherwise from the prices section of
    config.yml (price-source: prices-only uses config.yml alone). The owner, if online, sees one line per interval.
  - A crash never pays twice: each sale is written to plugins/NYR-ChunkHopper/sales.journal, and forced to disk, before
    the owner is paid. If the server dies before the world saves the sale, the world comes back with those items still
    in the hopper; they are taken out again the moment the chunk loads, before the hopper can pass them on, and staff are
    told. (A crash can cost a sale that was recorded but not yet paid; it never pays one twice.) Leave sales.journal in
    place; it empties itself as the world saves.
  - Protection: only the owner and staff can break a Chunk Hopper or open its filter. Break it with a pickaxe and it
    drops a Chunk Hopper item again, with its contents; by hand it does not break. Explosions and mobs cannot destroy
    it (explosion-proof).
  - Everything a Chunk Hopper knows (owner, filter, auto-sell, counters) is stored in the hopper itself, in the world
    save, so it survives restarts and chunk unloads; after a crash it is as the world was last saved.
    plugins/NYR-ChunkHopper/hoppers.yml lists every Chunk Hopper for /chunkhopper list and the limit; it is checked
    against the world whenever a chunk loads, so it never counts a Chunk Hopper a crash rolled back.


Commands
--------
  /chunkhopper give <player> [amount]   give Chunk Hopper items (staff)
  /chunkhopper list [player]            where your Chunk Hoppers are (staff: anyone's)
  /chunkhopper info                     the Chunk Hopper you are looking at: owner, filter, counters, contents
  /chunkhopper status                   settings and counts since start (staff)
  /chunkhopper reload                   reload config.yml (staff)
  Aliases: /ch, /chunkhoppers


Permissions
-----------
  nyrchunkhopper.use              place Chunk Hoppers, use your own, list and info  default: everyone
  nyrchunkhopper.admin            give, status, reload; break and open anyone's      default: op
  nyrchunkhopper.alerts           staff alerts (a refused sale, a filter icon found)  default: op
  nyrchunkhopper.limit.<n>        may own n Chunk Hoppers (the highest n counts)      default: nobody
  nyrchunkhopper.limit.unlimited  no limit                                            default: nobody


Removing the plugin
-------------------
  Placed Chunk Hoppers become plain hoppers with their contents. Put the plugin back and they are Chunk Hoppers again,
  with their owner, filter and counters.

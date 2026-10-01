NYR Guardian Suite @version@
============================

Four server plugins in one download. Each is its own plugin, so install all four or only the ones your server needs:

  NYR CombatTag Pro   players hit in PvP are tagged; logging out in a fight leaves a killable dummy that drops their gear
  NYR ChunkHopper     one hopper per chunk collects every drop in it as it spawns, filters it and can sell it for the owner
  NYR SmartTick       trading-hall villagers' brains sleep while the server is behind, and wake when it recovers
  NYR DupeSentry      stops the piston TNT, carpet and rail dupers, sand through end portals and tripwire hook dupers

The jars are the same ones sold on their own, so every plugin keeps its own config, commands and permissions.


In this download
----------------
  plugins/
      NYR-CombatTagPro-@version@.jar, NYR-ChunkHopper-@version@.jar, NYR-SmartTick-@version@.jar and
      NYR-DupeSentry-@version@.jar
  guides/
      one guide per plugin: what it does, its settings, commands and permissions
  defaults/<plugin>/config.yml
      the settings each plugin writes on its first start, for reference
  THIRD-PARTY-NOTICES.txt
      the one open-source library inside the jars (FoliaLib, MIT) and its licence


Requirements
------------
  - Java 21 or newer (Minecraft 26 servers need the Java they ship for).
  - A Paper, Purpur, Folia or Spigot server. No other plugin is needed, except for ChunkHopper's selling, which uses
    Vault and an economy plugin when you have them.

  Tested live with all four plugins installed together on Paper 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8,
  1.21.11 and 26.1.2, Purpur 1.21.11, Folia 1.21.11 and Spigot 1.21.11: 2,103 checks with players and staff, none
  failed, and no warning or error from the plugins in the server logs. On Paper 26.2 all four were tested to start and
  load cleanly.

  Two things to know before you install:
  - Paper, Purpur and Folia already stop the dupes NYR DupeSentry stops, unless you turned their unsupported-settings
    on; there DupeSentry stays idle and /dupesentry says so. It matters on Spigot, or on Paper with those settings on.
  - NYR SmartTick cannot pause villagers the server ticks as "inactive" (beyond the activation range) while spigot.yml
    has tick-inactive-villagers: true. Its guide explains the setting.


Installing
----------
  1. Copy the jars you want from plugins/ into your server's plugins folder.
  2. Start (or restart) the server. Each plugin creates its own folder, such as plugins/NYR-ChunkHopper/config.yml.
  3. Read a plugin's guide before changing its settings, then run its reload command.


Commands and permissions at a glance
------------------------------------
  /combattag                                                     everyone (nyrcombattagpro.check)
  /combattag tag | untag | dummies | status | reload             nyrcombattagpro.admin (op)
  /chunkhopper info | list                                       everyone (nyrchunkhopper.use)
  /chunkhopper give | status | reload                            nyrchunkhopper.admin (op)
  /smarttick status | sleep | wakeall | auto | check | reload    nyrsmarttick.admin (op)
  /dupesentry status | reload                                    nyrdupesentry.admin (op)

  Staff alerts go to players with each plugin's .alerts permission (op). The guides list every permission, including
  nyrcombattagpro.bypass and ChunkHopper's per-player limits.

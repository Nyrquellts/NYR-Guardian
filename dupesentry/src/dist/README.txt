NYR DupeSentry @version@
=======================

Stops the well-known mechanical dupes that Paper patches but Spigot does not: piston TNT dupers, carpet and rail dupers,
sand and other falling blocks duplicated through an end portal, and tripwire hook duplicators. Paper, Purpur and Folia
block the same dupes only while their "unsupported-settings" are left alone; turn those on and DupeSentry takes over.
It also closes a player's chest or animal screen the moment the chest or animal behind it is gone.

Nothing is tagged, tracked or deleted from inventories: each guard stops one known mechanism at the moment the second
copy would appear, and leaves everything else in the game as it was.


In this download
----------------
  NYR-DupeSentry-@version@.jar
      the plugin
  defaults/config.yml
      the settings the plugin writes on its first start, for reference
  THIRD-PARTY-NOTICES.txt
      the one open-source library inside the jar (FoliaLib, MIT) and its licence


Requirements
------------
  - Java 21 or newer (Minecraft 26 servers need the Java they ship for).
  - A Spigot, Paper, Purpur or Folia server. No other plugin is needed.

  Tested live on Spigot 1.21.11, where every dupe below was built and shown working without DupeSentry, then stopped
  with it; and on Paper 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.11 and 26.1.2, Purpur 1.21.11 and Folia
  1.21.11, each as installed (the server blocks these dupes itself and DupeSentry stays idle) and again with Paper's
  unsupported-settings turned on: TNT, carpet and rail dupers came back on all of them, the end portal dupe on all but
  Folia, the tripwire hook duplicator on 1.21.4 to 1.21.11; DupeSentry stopped every one. On Paper 26.2 it was tested to
  start and load cleanly.


Installing
----------
  1. Put NYR-DupeSentry-@version@.jar in the plugins folder and start (or restart) the server.
  2. Run /dupesentry status: it lists every guard and whether it is on, off, or idle because your server already blocks
     that dupe itself.
  3. Adjust plugins/NYR-DupeSentry/config.yml if you want, then run /dupesentry reload.


What each guard stops, and which servers already stop it
--------------------------------------------------------
  dupe                          guard             Spigot    Paper, Purpur and Folia
  TNT duper                     piston-dupes      open      blocked, unless allow-piston-duplication is true
  carpet duper                  piston-dupes      open      blocked, unless allow-piston-duplication is true
  rail duper                    piston-dupes      open      blocked, unless allow-piston-duplication is true
  falling block through an      portal-gravity    open      blocked, unless allow-unsafe-end-portal-teleportation is true
  end portal (sand, gravel,
  concrete powder, anvils)
  tripwire hook duplicator      tripwire-hooks    open      blocked from Paper 1.21.4 on, unless skip-tripwire-hook-placement-validation is true
  stale container screen        container-desync  (every server closes it only on the player's next tick)

  "Open" means the dupe works on that server as installed; each was built and shown working on Spigot 1.21.11
  without DupeSentry, and stopped with it. The settings named are Paper's, under unsupported-settings in
  config/paper-global.yml. Where the server blocks a dupe itself, DupeSentry's matching guard stays idle: a second fix on
  top of Paper's would take away the one copy Paper leaves (Paper moves a pushed carpet's real state, so the carpet that
  broke off is the only one). /dupesentry status says which guards are idle and why. Turn the Paper setting on and the
  guard works; DupeSentry reads it at every start and reload.

  Technical servers: Paper's allow-piston-duplication turns TNT, carpet and rail duping on together. If you turned it on
  for world eaters and tunnel bores, set guards.piston-dupes.tnt to false in config.yml: TNT dupers keep working, and
  carpet and rail dupers are still stopped.


What DupeSentry does when it stops a dupe
-----------------------------------------
  - TNT duper: the pushed TNT is not lit; the piston moves it like any other block. TNT that is pushed first and lit
    later, where it arrives (TNT cannons, bombers, TNT pushed onto redstone), is untouched.
  - Carpet or rail duper: the carpet or rail that breaks off does not drop; the piston moves it. A carpet on a block
    that is pushed away from under it still drops once, as always.
  - Falling block through an end portal: the falling block does not use the end portal (to or from the End); it lands,
    or drops, on this side once. Items, mobs and players use end portals as usual; end gateways are left alone (they
    copy nothing). Set portal-gravity.nether-portals to true to keep falling blocks out of nether portals as well.
  - Tripwire hook duplicator: the hook that broke off keeps its one drop; the copy the game puts back is removed without
    a drop (or its second drop is cancelled). A hook a player places there is left alone.
  Each time, staff with nyrdupesentry.alerts are told what was stopped, where, and which player was nearest.


Stale container screens
-----------------------
  The game closes a chest's screen once the chest is gone or out of reach, and an animal's once the animal is gone, but
  only when the screen checks itself on the player's next tick. DupeSentry closes it at once: when the chest, barrel,
  furnace, hopper, crafting table or similar block is broken, blown up or unloaded, when the horse, llama, donkey,
  chest minecart or chest boat dies, is destroyed or goes to another world, and when the player quits, dies, changes
  world or is teleported more than 8 blocks away from it. Menus other plugins open belong to no block or entity and are
  never touched.

  With cursor-item on, an item a player holds on the cursor when quitting goes into their inventory before it is saved
  (the server would otherwise drop it on the ground where they logged off), and a dying player's goes with their other
  drops.

  This is a clean-up that closes a known gap; no specific dupe on current servers is known to go through it, and none
  is claimed.


Commands
--------
  /dupesentry status        every guard, on, off or idle and why, and what it stopped since start
  /dupesentry reload        reload config.yml
  Alias: /dsentry


Permissions
-----------
  nyrdupesentry.admin      /dupesentry status and reload                      default: op
  nyrdupesentry.alerts     staff alerts when a dupe is stopped                  default: op


Staff alerts and records
------------------------
  Staff with nyrdupesentry.alerts are told which dupe was stopped, the world and position, and the nearest player. The
  same line goes to the console and to plugins/NYR-DupeSentry/alerts.log. A duper that keeps running in one place is
  reported at most once every 10 seconds per kind and chunk (alerts.cooldown-seconds); /dupesentry status counts every
  one.

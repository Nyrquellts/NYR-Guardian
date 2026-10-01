NYR CombatTag Pro @version@
=======================

Players hit in PvP are tagged for a while (15 seconds by default) and see a countdown on their action bar. While tagged,
commands such as /spawn, /home and /tpa are refused. A tagged player who logs out leaves a dummy behind where they stood:
if other players kill it, it drops what the player carried and the player dies the next time they join; if it survives
its time, it vanishes and the player keeps everything. No Citizens or other NPC plugin is needed.


In this download
----------------
  NYR-CombatTagPro-@version@.jar
      the plugin
  defaults/config.yml
      the settings the plugin writes on its first start, for reference
  THIRD-PARTY-NOTICES.txt
      the one open-source library inside the jar (FoliaLib, MIT) and its licence


Requirements
------------
  - Java 21 or newer (Minecraft 26 servers need the Java they ship for).
  - A Paper, Purpur, Folia or Spigot server. No other plugin is needed.

  Tested live on Paper 1.20.6, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.11 and 26.1.2, Purpur 1.21.11, Folia 1.21.11
  and Spigot 1.21.11, with test players: a hit tagging both players, the action-bar countdown, a refused /spawn, logging
  out in combat, the dummy killed and its owner dying on joining with nothing duplicated, the owner coming back before
  the dummy died, a dummy running out of time, a hard kill of the server with a kill waiting, and a peaceful world. On
  Paper 26.2 it was tested to start and load cleanly.


Installing
----------
  1. Put NYR-CombatTagPro-@version@.jar in the plugins folder and start (or restart) the server.
  2. Adjust plugins/NYR-CombatTagPro/config.yml if you like, then run /combattag reload.


How tagging works
-----------------
  - A player who hits another player, or hits them with an arrow, trident, snowball, egg, fireball, wind charge or thrown
    potion, sets off TNT that hurts them, or throws a potion whose cloud hurts them, puts both of them in combat for
    combat-seconds (default 15). Every new hit starts the time again.
  - A harmful splash or lingering potion (poison, harming, slowness, weakness and the like) thrown at another player tags
    both as well (tag-on.harmful-potions). Healing potions tag nobody.
  - Mobs hitting a player tag nobody unless tag-on.mobs is true; then only the player is tagged.
  - Nobody is tagged in creative or spectator mode, with nyrcombattagpro.bypass, in a world listed under worlds.disabled,
    or by a hit another plugin cancelled (so a region where a protection plugin turns PvP off tags nobody).
  - Combat ends when the time runs out ("You are no longer in combat"), when the player dies, and when they log out.
  - The countdown shows on the action bar every second (messages.action-bar; "" switches it off).


Blocked commands
----------------
  - blocked-commands.list names the commands refused in combat (blacklist) or, with mode: whitelist, the only ones
    allowed. "/essentials:home" counts as "home" and upper case does not matter.
  - List a plugin's other aliases too when they are separate commands (Essentials also has ehome, etpa and so on).
  - /combattag always works, so players can check their timer.


The dummy
---------
  - On servers that have mannequins (Minecraft 1.21.9 and newer: Paper, Purpur, Folia and Spigot) the dummy is a
    mannequin given the player's own profile (which carries their skin when the server knows it), wearing their armour
    and holding what they held, with their name above it and "Logged out in combat" below. On older servers it is a husk
    (dummy.fallback-entity) with its AI switched off, wearing the player's armour and holding what they held. A husk never
    burns in daylight.
  - It has the player's health and max health, takes damage like the player would, and stands for dummy.seconds
    (default 30).
  - If a player kills it: what the logged-out player carried (inventory, armour, off hand) drops where it died, with the
    experience a player's death drops, the killer is credited with a player kill, and everyone is told who killed it.
    When the player next joins, those items and that experience are taken from them and they die (the death message says
    who killed them while they were away), so nothing drops twice. That death counts as nobody's kill, so the killer is
    credited once. On Spigot the death comes five seconds after joining (Spigot cannot make the game forget who last hit
    the player, and the game only remembers that for five seconds). With keepInventory on in that world nothing drops and
    the player keeps their items, but still dies on joining.
  - If the player comes back before the dummy dies, the dummy goes, the player gets the dummy's health (logging out never
    heals) and is in combat again.
  - If the dummy survives its time, it vanishes and the player keeps everything.
  - The dummy's own equipment is only a copy to show what the player wore: it never drops.
  - Why nothing can be duplicated: the kill is written to plugins/NYR-CombatTagPro/data/killed/<uuid>.yml, and forced to
    the disk, before anything drops. A crash after that can only lose the dropped items (the world had not saved them yet);
    the player still loses them on joining. If the record cannot be written at all, nothing drops and the player keeps
    their items. The owner's return, the dummy's death and its timer can happen at the same moment on Folia; exactly one of
    them wins.
  - A player kicked by staff or by another plugin leaves no dummy unless dummy.on-kick is true. Players leaving because
    the server stops leave none either.
  - A peaceful world removes monsters such as the husk the moment they spawn. On Paper 1.21.8 and newer the plugin keeps
    the husk anyway; on older servers without mannequins a villager (dummy.peaceful-fallback-entity) stands in there.


Commands
--------
  /combattag                  whether you are in combat and for how long (everyone)
  /combattag tag <player>     put a player in combat (staff)
  /combattag untag <player>   take a player out of combat (staff)
  /combattag dummies          standing dummies: whose, where, their health and time left (staff)
  /combattag status           settings and counts since start (staff)
  /combattag reload           reload config.yml (staff)
  Aliases: /ct, /combat


Permissions
-----------
  nyrcombattagpro.check    /combattag                                            default: everyone
  nyrcombattagpro.admin    tag, untag, dummies, status and reload                default: op
  nyrcombattagpro.alerts   staff chat alerts about combat logs and killed dummies default: op
  nyrcombattagpro.bypass   never tagged, never refused a command, no dummy       default: nobody
  nyrcombattagpro.*        check, admin and alerts; bypass is not included, so staff are still tagged


Limits
------
  - End crystals have no reliable owner, so an end crystal explosion tags nobody. Neither do beds or respawn anchors set
    off in the wrong dimension, TNT minecarts, or lava and fire a player placed.
  - A tamed wolf or other pet biting a player does not tag its owner.
  - A reload keeps players in combat and keeps standing dummies. Disabling the plugin or restarting the server removes
    standing dummies (a dummy is never saved with the world) and their players keep everything. Kill records wait on disk
    for their players across restarts, a crash included.
  - Networks (BungeeCord, Velocity): a dummy and its kill belong to the server where the player logged out. The kill is
    applied when the player next joins that server. With a plugin that shares inventories between servers, a player could
    carry their items to another server before that happens; do not rely on this plugin to stop that.
  - Region and anti-cheat plugins: a hit another plugin cancels tags nobody. A dummy whose spawn another plugin cancels
    (a mob limiter, a region that denies mobs) is spawned anyway; if it still does not appear, the player keeps everything
    and staff are alerted.


Removing the plugin
-------------------
  Standing dummies are removed when the plugin is disabled. Records in plugins/NYR-CombatTagPro/data/killed are only read
  by this plugin: players who had a dummy killed and never came back keep their items if the plugin is removed.

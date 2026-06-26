# HighBuildLimit

Raises the build height in Minecraft so you can build much higher than normal. Works on Paper 1.21.11, server side only. Players do not need any mods or resource packs.

## What it does

- Overworld: build up to Y 2031 (normal top is 319)
- End: build up to Y 2031 (normal top is 255)
- Nether: left alone on purpose (see notes below)

The bottom of each world stays exactly where vanilla puts it. Only the top is raised.

## Why it is a datapack and not a plugin

Build height in Minecraft is set by the dimension type, which is data only. A plugin cannot actually resize world storage while the server is running, so a datapack is the correct way to do this.

There is an optional plugin included too, but it only reports the current height and runs a few commands. It does not change the height itself.

## The real Minecraft limits

- The highest block you can place is Y 2031. You cannot go higher than this.
- height can be up to 4064 and must be a multiple of 16
- min_y can go down to -2032 and must be a multiple of 16
- min_y plus height must be 2032 or less
- Infinite height is not possible

This pack uses:

- Overworld: min_y -64, height 2096
- End: min_y 0, height 2032

## Install (datapack)

1. Stop the server.
2. Back up your world folder.
3. Copy the folder `datapack/HighBuildLimit` into your main world's datapacks folder. You should end up with:
   `world/datapacks/HighBuildLimit/pack.mcmeta`
4. Start the server.
5. Run `/datapack list` and check HighBuildLimit is enabled. If it is not, run `/datapack enable "file/HighBuildLimit"`.

## Install (optional plugin)

1. Build it (see below) to get `HighBuildLimit-1.0.0.jar`.
2. Put the jar in your `plugins` folder.
3. Start the server.

Commands:

- `/highbuildlimit version`
- `/highbuildlimit info`
- `/highbuildlimit reload`

## Build the plugin

From the `plugin` folder:

```
gradle build
```

Needs Java 21. The jar ends up in `plugin/build/libs/`.

## Warnings

- Always back up your world before installing.
- Adding the pack to an existing world is the safe direction. It keeps the world bottom the same and only adds space on top, so old chunks stay lined up.
- Removing the pack later is the risky part. If you build above the old vanilla top (Y 319 overworld, Y 255 end) and then remove the pack, those high blocks are deleted for good.
- Do not lower min_y on an existing world. That can corrupt terrain. Only do that on a brand new world.

## Notes on the Nether

The Nether has a solid bedrock roof near Y 127 that comes from how the dimension generates, not from the height setting. Raising the Nether height just adds empty space above the roof that is hard to reach, and it can mess with mob spawning and lighting. So the Nether is left at vanilla.

## Lighting (building high without darkening the ground)

If you build a big solid platform up high, the ground under it goes dark. That is just how Minecraft skylight works, and there is no way to turn it off server side.

What does not work:

- A datapack cannot change this. Light rules are not data driven.
- A normal plugin cannot change this. There is no API for it.
- The only true fix is editing the light engine with NMS, which is fragile and can break lighting, so this project does not do that.

What actually works:

- Build the high platform out of glass. Glass lets skylight through like it is not there, so the ground below stays lit. Glass is still solid and walkable. Do not use tinted glass, it blocks light.
- If you want it to look solid, place invisible light blocks under it with `/setblock ~ ~ ~ minecraft:light[level=15]`. Sea lanterns and glowstone also work.
- Night vision on players only changes what they see. The area is still dark for mobs and grass, so it is a workaround, not a real fix.

All the working options above are server side, vanilla, and safe for existing worlds.

## ShadowSafe (stop tall builds from shadowing the ground)

ShadowSafe is the safe way to deal with shadows from high structures. You build normally,
then convert full blocks above a chosen Y level. Each converted block is replaced with a
transparent proxy block (glass by default), and a BlockDisplay entity is spawned at that
spot showing the original block. Glass does not block skylight, so the ground below stays
lit, while the build still looks like the original block because of the display.

It uses only the normal Bukkit/Paper API: blocks, BlockDisplay entities, and the
scheduler. No NMS, no light engine patching, no reflection into internals, no resource
pack.

Why this is safe: it never touches the light engine, heightmaps, or chunk internals. The
real block simply becomes glass (which lets skylight through), and the look is carried by
a normal entity. Entities do not block light, so there is no shadow from them.

Workflow:

1. Build your structure normally.
2. Stand near it and run `/hbl shadowsafe scan <radius>` to see the block types up there.
3. Run `/hbl shadowsafe dryrun <radius>` to see how many blocks would convert.
4. Run `/hbl shadowsafe convert <radius>` to convert them.
5. Run `/hbl shadowsafe restore <radius>` to undo and put the original blocks back.

Config:

```yaml
shadow-safe:
  enabled: true
  min-y: 300
  proxy-block: glass
  max-radius: 64
  batch-size: 1000
```

Commands (need permission `highbuildlimit.admin`):

- `/hbl shadowsafe info` explains the system.
- `/hbl shadowsafe scan <radius>` lists block types above min-y near you. Changes nothing.
- `/hbl shadowsafe dryrun <radius>` shows how many blocks would convert. Changes nothing.
- `/hbl shadowsafe convert <radius>` converts supported full blocks above min-y.
- `/hbl shadowsafe restore <radius>` removes the displays and restores the original blocks.
- `/hbl shadowsafe list` shows how many converted blocks are tracked.
- `/hbl shadowsafe reload` reloads the config and saved data.

What converts and what does not:

- Converts: simple full opaque cubes (stone, planks, wool, concrete, logs, etc.).
- Skipped: containers and tile entities (chests, barrels, furnaces, signs, beds, command
  blocks, spawners, anything with an inventory or stored data).
- Skipped: detail blocks (stairs, slabs, fences, walls, doors, trapdoors, buttons,
  pressure plates, rails, panes, plants).
- Skipped: air, fluids, bedrock, barriers, light blocks.

Safety and performance:

- Blocks below min-y are never touched.
- Radius is capped by `max-radius`. Work is batched (`batch-size` per tick) so the server
  does not freeze. Only already-loaded chunks are processed.
- Conversions are saved to `shadowsafe-data.yml` and the displays are persistent, so they
  survive restarts. On chunk load the plugin respawns any missing display, recovers data
  from a display if the file was lost, and removes duplicate displays.
- Each display is tagged in its persistent data so the plugin only ever touches its own.

Limits to know:

- Collision is the proxy block's collision. With glass, full blocks still collide like
  full blocks, so you can still walk on a converted floor.
- This version handles full blocks only. Detail blocks (stairs, fences, slabs) are skipped
  and can still cast small shadows. Large full-block floors, walls, and roofs cause most
  of the visible shadow, so converting those gets you most of the benefit.
- If a player breaks the glass after conversion, the display can be left floating. Run
  restore, or break/replace the spot, to clean it up.
- The proxy must be light-safe. Glass and stained glass work. The plugin warns if you set
  a proxy that still blocks light.

## Quick summary

- The datapack changes the height, the plugin does not.
- Max height is Y 2031. Infinite is not possible.
- The world bottom is never changed, only the top.
- Back up before installing. Removing the pack after building high deletes the high blocks.

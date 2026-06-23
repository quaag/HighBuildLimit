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

## Quick summary

- The datapack changes the height, the plugin does not.
- Max height is Y 2031. Infinite is not possible.
- The world bottom is never changed, only the top.
- Back up before installing. Removing the pack after building high deletes the high blocks.

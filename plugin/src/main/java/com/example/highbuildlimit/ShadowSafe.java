package com.example.highbuildlimit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * ShadowSafe: stop tall builds from shadowing the ground, the safe way.
 *
 * Builders build normally. An admin then converts full blocks above a Y level:
 * the real block is replaced with a transparent proxy (default glass), and a
 * BlockDisplay entity is spawned at that spot showing the original block. Glass
 * does not block skylight, so the ground below stays lit, while the build still
 * looks like the original block because of the display.
 *
 * No NMS, no reflection into internals, no light-engine patching. Only the public
 * Bukkit/Paper API (blocks, BlockDisplay entities, PDC, the scheduler).
 *
 * Safety:
 *   - never touches blocks below min-y,
 *   - only converts simple full opaque cubes (occluding, solid, no block entity),
 *   - skips containers, tile entities, detail blocks, fluids, bedrock, barriers, light,
 *   - radius capped by max-radius, work is batched across ticks to avoid freezing,
 *   - conversions are saved to disk and self-heal on chunk load (respawn/recover/dedupe).
 */
final class ShadowSafe implements Listener {

    private final HighBuildLimitPlugin plugin;

    private boolean enabled;
    private int minY;
    private Material proxyMaterial = Material.GLASS;
    private BlockData proxyData = Material.GLASS.createBlockData();
    private int maxRadius;
    private int batchSize;

    private final NamespacedKey markerKey;
    private final NamespacedKey origKey;
    private final NamespacedKey proxyKey;

    private final File dataFile;

    // posKey -> conversion, and chunkKey -> conversions in that chunk.
    private final Map<String, Conversion> conversions = new HashMap<>();
    private final Map<String, List<Conversion>> byChunk = new HashMap<>();

    private boolean busy;
    private BukkitTask activeTask;
    private boolean savePending;

    ShadowSafe(HighBuildLimitPlugin plugin) {
        this.plugin = plugin;
        this.markerKey = new NamespacedKey(plugin, "shadowsafe");
        this.origKey = new NamespacedKey(plugin, "original");
        this.proxyKey = new NamespacedKey(plugin, "proxy");
        this.dataFile = new File(plugin.getDataFolder(), "shadowsafe-data.yml");
    }

    // ---- lifecycle -------------------------------------------------------

    void load() {
        var c = plugin.getConfig();
        enabled = c.getBoolean("shadow-safe.enabled", true);
        minY = c.getInt("shadow-safe.min-y", 300);
        maxRadius = Math.max(0, c.getInt("shadow-safe.max-radius", 64));
        batchSize = Math.max(1, c.getInt("shadow-safe.batch-size", 1000));

        String pName = c.getString("shadow-safe.proxy-block", "glass");
        Material pm = Material.matchMaterial(pName == null ? "glass" : pName);
        if (pm == null || !pm.isBlock()) {
            plugin.getLogger().warning("[shadow-safe] invalid proxy-block '" + pName + "', using glass.");
            pm = Material.GLASS;
        }
        proxyMaterial = pm;
        proxyData = proxyMaterial.createBlockData();
        if (proxyMaterial.isOccluding()) {
            plugin.getLogger().warning("[shadow-safe] proxy-block '" + lc(proxyMaterial)
                    + "' still blocks light; shadows will remain. Use glass or stained glass.");
        }

        loadData();
    }

    /** Verify already-loaded chunks once at startup (their load event fired before us). */
    void verifyLoadedChunks() {
        for (World w : Bukkit.getWorlds()) {
            for (Chunk c : w.getLoadedChunks()) {
                verifyChunk(c);
            }
        }
    }

    void shutdown() {
        if (activeTask != null) {
            activeTask.cancel();
            activeTask = null;
        }
        busy = false;
        saveData();
    }

    // ---- commands --------------------------------------------------------

    boolean handleCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("highbuildlimit.admin")) {
            sender.sendMessage(ChatColor.RED + "You need permission highbuildlimit.admin.");
            return true;
        }
        String action = (args.length >= 2) ? args[1].toLowerCase(Locale.ROOT) : "info";
        switch (action) {
            case "info":    info(sender); return true;
            case "list":    list(sender); return true;
            case "reload":  load(); sender.sendMessage(ChatColor.GREEN + "ShadowSafe config and data reloaded."); return true;
            case "scan":    startColumnJob(sender, args, Mode.SCAN); return true;
            case "dryrun":  startColumnJob(sender, args, Mode.DRYRUN); return true;
            case "convert": startColumnJob(sender, args, Mode.CONVERT); return true;
            case "restore": startRestoreJob(sender, args); return true;
            default:
                sender.sendMessage(ChatColor.GRAY
                        + "Usage: /hbl shadowsafe <info|scan|dryrun|convert|restore|list|reload> [radius]");
                return true;
        }
    }

    private enum Mode { SCAN, DRYRUN, CONVERT }

    private void info(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "=== ShadowSafe (BlockDisplay) ===");
        s.sendMessage(ChatColor.GRAY + "Replaces full blocks above Y=" + minY + " with " + lc(proxyMaterial)
                + " and shows the original");
        s.sendMessage(ChatColor.GRAY + "block via a BlockDisplay entity. " + lc(proxyMaterial)
                + " does not block skylight, so the");
        s.sendMessage(ChatColor.GRAY + "ground stays lit while the build still looks the same.");
        s.sendMessage(ChatColor.GRAY + "enabled: " + ChatColor.WHITE + enabled
                + ChatColor.GRAY + "  min-y: " + ChatColor.WHITE + minY
                + ChatColor.GRAY + "  max-radius: " + ChatColor.WHITE + maxRadius
                + ChatColor.GRAY + "  batch: " + ChatColor.WHITE + batchSize);
        s.sendMessage(ChatColor.GRAY + "Workflow: build -> scan -> dryrun -> convert (restore to undo).");
        s.sendMessage(ChatColor.YELLOW + "Only simple full blocks convert. Containers, tile entities, and detail");
        s.sendMessage(ChatColor.YELLOW + "blocks (stairs/slabs/fences/panes/plants/etc.) are skipped by design.");
        s.sendMessage(ChatColor.YELLOW + "Blocks below min-y are never touched. No resource pack needed.");
    }

    private void list(CommandSender s) {
        s.sendMessage(ChatColor.GOLD + "ShadowSafe converted blocks: " + ChatColor.WHITE + conversions.size());
        Map<String, Integer> perWorld = new LinkedHashMap<>();
        for (Conversion c : conversions.values()) {
            World w = Bukkit.getWorld(c.world);
            String name = (w != null) ? w.getName() : c.world.toString();
            perWorld.merge(name, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : perWorld.entrySet()) {
            s.sendMessage(ChatColor.GRAY + "  " + e.getKey() + ": " + ChatColor.WHITE + e.getValue());
        }
        s.sendMessage(ChatColor.DARK_GRAY + "Displays are persistent entities; they reload with their chunks.");
    }

    // ---- scan / dryrun / convert (column-batched) ------------------------

    private void startColumnJob(CommandSender sender, String[] args, Mode mode) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Run this as a player (it works around your position).");
            return;
        }
        if (busy) {
            sender.sendMessage(ChatColor.RED + "A ShadowSafe operation is already running. Please wait.");
            return;
        }
        if (mode == Mode.CONVERT && !enabled) {
            sender.sendMessage(ChatColor.RED + "shadow-safe is disabled in config (shadow-safe.enabled: false).");
            return;
        }
        Integer rad = parseRadius(sender, args, mode.name().toLowerCase(Locale.ROOT));
        if (rad == null) {
            return;
        }
        int radius = rad;
        if (radius > maxRadius) {
            radius = maxRadius;
            sender.sendMessage(ChatColor.YELLOW + "Radius capped to " + maxRadius + ".");
        }

        final World w = player.getWorld();
        final int startY = Math.max(minY, w.getMinHeight());
        final int topY = w.getMaxHeight() - 1;
        if (startY > topY) {
            sender.sendMessage(ChatColor.YELLOW + "min-y (" + minY + ") is above this world's build height; nothing to do.");
            return;
        }

        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        final Deque<int[]> cols = new ArrayDeque<>();
        boolean skipped = false;
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                if (!w.isChunkLoaded(x >> 4, z >> 4)) {
                    skipped = true;
                    continue;
                }
                int hi = w.getHighestBlockYAt(x, z);
                if (hi < startY) {
                    continue;
                }
                cols.add(new int[]{x, z, Math.min(hi, topY)});
            }
        }

        final String label = (mode == Mode.SCAN) ? "Scan" : (mode == Mode.DRYRUN) ? "Dry run" : "Convert";
        if (cols.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "[" + label + "] No blocks above min-y in range.");
            return;
        }

        final boolean skippedUnloaded = skipped;
        final Map<Material, Integer> counts = new HashMap<>();
        busy = true;
        sender.sendMessage(ChatColor.GRAY + "[" + label + "] started: " + cols.size()
                + " columns, batch " + batchSize + "/tick.");

        activeTask = new BukkitRunnable() {
            int[] cur = null;
            int y;
            long matched = 0;

            @Override
            public void run() {
                int n = 0;
                while (n < batchSize) {
                    if (cur == null) {
                        cur = cols.poll();
                        if (cur == null) {
                            finish();
                            return;
                        }
                        y = startY;
                    }
                    Block b = w.getBlockAt(cur[0], y, cur[1]);
                    if (mode == Mode.SCAN) {
                        if (!b.getType().isAir()) {
                            counts.merge(b.getType(), 1, Integer::sum);
                        }
                    } else if (isConvertible(b) && !conversions.containsKey(posKey(w.getUID(), cur[0], y, cur[1]))) {
                        counts.merge(b.getType(), 1, Integer::sum);
                        matched++;
                        if (mode == Mode.CONVERT) {
                            convertBlock(b);
                        }
                    }
                    n++;
                    y++;
                    if (y > cur[2]) {
                        cur = null;
                    }
                }
            }

            private void finish() {
                cancel();
                busy = false;
                activeTask = null;
                if (mode == Mode.CONVERT) {
                    saveData();
                }
                report(sender, label, mode, counts, matched, skippedUnloaded);
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void report(CommandSender s, String label, Mode mode, Map<Material, Integer> counts,
                        long matched, boolean skippedUnloaded) {
        s.sendMessage(ChatColor.GOLD + "=== ShadowSafe " + label + " ===");
        if (counts.isEmpty()) {
            s.sendMessage(ChatColor.GRAY + (mode == Mode.SCAN
                    ? "No blocks found above min-y." : "No convertible blocks found."));
        } else {
            List<Map.Entry<Material, Integer>> sorted = counts.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<Material, Integer>>comparingInt(Map.Entry::getValue).reversed())
                    .toList();
            int shown = 0;
            for (Map.Entry<Material, Integer> e : sorted) {
                if (shown++ >= 40) {
                    s.sendMessage(ChatColor.DARK_GRAY + "  ... and " + (sorted.size() - 40) + " more types");
                    break;
                }
                s.sendMessage(ChatColor.GRAY + "  " + lc(e.getKey()) + ": " + ChatColor.WHITE + e.getValue());
            }
        }
        switch (mode) {
            case SCAN -> s.sendMessage(ChatColor.GRAY + "Nothing changed. Use dryrun/convert next.");
            case DRYRUN -> s.sendMessage(ChatColor.AQUA + "Would convert " + matched + " block(s). Nothing changed.");
            case CONVERT -> s.sendMessage(ChatColor.GREEN + "Converted " + matched
                    + " block(s) to " + lc(proxyMaterial) + " with displays.");
        }
        if (skippedUnloaded) {
            s.sendMessage(ChatColor.YELLOW + "Some columns were skipped (chunks not loaded).");
        }
    }

    // ---- restore (record-batched) ----------------------------------------

    private void startRestoreJob(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Run this as a player (it works around your position).");
            return;
        }
        if (busy) {
            sender.sendMessage(ChatColor.RED + "A ShadowSafe operation is already running. Please wait.");
            return;
        }
        Integer rad = parseRadius(sender, args, "restore");
        if (rad == null) {
            return;
        }
        int radius = Math.min(rad, maxRadius);
        if (rad > maxRadius) {
            sender.sendMessage(ChatColor.YELLOW + "Radius capped to " + maxRadius + ".");
        }

        World w = player.getWorld();
        UUID wuid = w.getUID();
        int px = player.getLocation().getBlockX();
        int pz = player.getLocation().getBlockZ();
        final Deque<Conversion> work = new ArrayDeque<>();
        for (Conversion c : conversions.values()) {
            if (c.world.equals(wuid) && Math.abs(c.x - px) <= radius && Math.abs(c.z - pz) <= radius) {
                work.add(c);
            }
        }
        if (work.isEmpty()) {
            sender.sendMessage(ChatColor.GRAY + "[Restore] No converted blocks in range.");
            return;
        }

        final int total = work.size();
        busy = true;
        sender.sendMessage(ChatColor.GRAY + "[Restore] started: " + total + " block(s), batch " + batchSize + "/tick.");
        activeTask = new BukkitRunnable() {
            long restored = 0;

            @Override
            public void run() {
                int n = 0;
                while (n < batchSize && !work.isEmpty()) {
                    if (restoreOne(work.poll())) {
                        restored++;
                    }
                    n++;
                }
                if (work.isEmpty()) {
                    cancel();
                    busy = false;
                    activeTask = null;
                    saveData();
                    sender.sendMessage(ChatColor.GREEN + "[Restore] complete: " + restored + "/" + total + " restored.");
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    // ---- core convert / restore ------------------------------------------

    private boolean isConvertible(Block b) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid()) {
            return false;
        }
        if (m == Material.BEDROCK || m == Material.BARRIER || m == Material.LIGHT) {
            return false;
        }
        if (m == proxyMaterial) {
            return false;
        }
        if (!m.isSolid() || !m.isOccluding()) {
            return false; // excludes glass, slabs, stairs, fences, panes, plants, leaves, etc.
        }
        return !(b.getState() instanceof TileState); // excludes chests/furnaces/signs/spawners/command blocks/etc.
    }

    private void convertBlock(Block b) {
        BlockData orig = b.getBlockData();
        b.setBlockData(proxyData, false);
        BlockDisplay d = spawnDisplay(b.getWorld(), b.getX(), b.getY(), b.getZ(), orig);
        Conversion conv = new Conversion(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ(),
                orig.getAsString(), lc(proxyMaterial), d.getUniqueId());
        index(conv);
    }

    private boolean restoreOne(Conversion c) {
        World w = Bukkit.getWorld(c.world);
        if (w == null) {
            return false;
        }
        if (c.display != null) {
            Entity e = Bukkit.getEntity(c.display);
            if (e != null) {
                e.remove();
            }
        }
        Block b = w.getBlockAt(c.x, c.y, c.z);
        removeMarkedDisplaysAt(b);
        Material proxyM = Material.matchMaterial(c.proxy);
        if (proxyM != null && b.getType() == proxyM) {
            BlockData od = safeBlockData(c.original);
            if (od != null) {
                b.setBlockData(od, false);
            }
        }
        unindex(c);
        return true;
    }

    private BlockDisplay spawnDisplay(World w, int x, int y, int z, BlockData show) {
        Location loc = new Location(w, x, y, z); // block min corner = exact grid alignment
        return w.spawn(loc, BlockDisplay.class, bd -> {
            bd.setBlock(show);
            bd.setPersistent(true);
            bd.setGlowing(false);
            bd.setInterpolationDuration(0);
            bd.setInterpolationDelay(0);
            PersistentDataContainer pdc = bd.getPersistentDataContainer();
            pdc.set(markerKey, PersistentDataType.BYTE, (byte) 1);
            pdc.set(origKey, PersistentDataType.STRING, show.getAsString());
            pdc.set(proxyKey, PersistentDataType.STRING, lc(proxyMaterial));
        });
    }

    private void removeMarkedDisplaysAt(Block b) {
        Location center = new Location(b.getWorld(), b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
        for (Entity e : b.getWorld().getNearbyEntities(center, 0.5, 0.5, 0.5)) {
            if (e instanceof BlockDisplay bd && isMarked(bd)) {
                bd.remove();
            }
        }
    }

    // ---- chunk-load self-healing -----------------------------------------

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        verifyChunk(event.getChunk());
    }

    /** Respawn missing displays, recover orphan displays from PDC, remove duplicates. */
    private void verifyChunk(Chunk c) {
        boolean dirty = false;
        Map<String, BlockDisplay> seen = new HashMap<>();
        for (Entity ent : c.getEntities()) {
            if (!(ent instanceof BlockDisplay bd) || !isMarked(bd)) {
                continue;
            }
            Location l = bd.getLocation();
            String pk = posKey(c.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
            if (seen.containsKey(pk)) {
                bd.remove(); // duplicate at same block
                continue;
            }
            seen.put(pk, bd);
            Conversion rec = conversions.get(pk);
            if (rec == null) {
                Conversion recovered = recoverFromPdc(bd);
                if (recovered != null) {
                    index(recovered);
                    dirty = true;
                } else {
                    bd.remove();
                }
            } else if (rec.display == null || !rec.display.equals(bd.getUniqueId())) {
                rec.display = bd.getUniqueId();
                dirty = true;
            }
        }

        List<Conversion> recs = byChunk.get(chunkKey(c.getWorld().getUID(), c.getX(), c.getZ()));
        if (recs != null) {
            for (Conversion rec : new ArrayList<>(recs)) {
                String pk = posKey(rec.world, rec.x, rec.y, rec.z);
                if (seen.containsKey(pk)) {
                    continue;
                }
                Block b = c.getWorld().getBlockAt(rec.x, rec.y, rec.z);
                Material proxyM = Material.matchMaterial(rec.proxy);
                if (proxyM != null && b.getType() == proxyM) {
                    BlockData od = safeBlockData(rec.original);
                    if (od != null) {
                        BlockDisplay d = spawnDisplay(c.getWorld(), rec.x, rec.y, rec.z, od);
                        rec.display = d.getUniqueId();
                        dirty = true;
                    }
                }
            }
        }
        if (dirty) {
            scheduleSave();
        }
    }

    private Conversion recoverFromPdc(BlockDisplay bd) {
        PersistentDataContainer pdc = bd.getPersistentDataContainer();
        String orig = pdc.get(origKey, PersistentDataType.STRING);
        if (orig == null) {
            return null;
        }
        String proxy = pdc.get(proxyKey, PersistentDataType.STRING);
        if (proxy == null) {
            proxy = lc(proxyMaterial);
        }
        Location l = bd.getLocation();
        return new Conversion(bd.getWorld().getUID(), l.getBlockX(), l.getBlockY(), l.getBlockZ(),
                orig, proxy, bd.getUniqueId());
    }

    private boolean isMarked(BlockDisplay bd) {
        return bd.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    // ---- data file -------------------------------------------------------

    private void loadData() {
        conversions.clear();
        byChunk.clear();
        if (!dataFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        for (Map<?, ?> m : y.getMapList("conversions")) {
            try {
                UUID world = UUID.fromString(String.valueOf(m.get("world")));
                int x = ((Number) m.get("x")).intValue();
                int yy = ((Number) m.get("y")).intValue();
                int z = ((Number) m.get("z")).intValue();
                String original = String.valueOf(m.get("original"));
                String proxy = String.valueOf(m.get("proxy"));
                Object disp = m.get("display");
                UUID display = (disp == null) ? null : UUID.fromString(String.valueOf(disp));
                index(new Conversion(world, x, yy, z, original, proxy, display));
            } catch (Exception ex) {
                plugin.getLogger().warning("[shadow-safe] skipped bad record: " + ex.getMessage());
            }
        }
    }

    private void saveData() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>(conversions.size());
        for (Conversion c : conversions.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("world", c.world.toString());
            m.put("x", c.x);
            m.put("y", c.y);
            m.put("z", c.z);
            m.put("original", c.original);
            m.put("proxy", c.proxy);
            if (c.display != null) {
                m.put("display", c.display.toString());
            }
            list.add(m);
        }
        y.set("conversions", list);
        try {
            File parent = dataFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            y.save(dataFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("[shadow-safe] could not save data: " + ex.getMessage());
        }
    }

    private void scheduleSave() {
        if (savePending || !plugin.isEnabled()) {
            return;
        }
        savePending = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            savePending = false;
            saveData();
        }, 100L);
    }

    // ---- helpers ---------------------------------------------------------

    private Integer parseRadius(CommandSender s, String[] args, String sub) {
        if (args.length < 3) {
            s.sendMessage(ChatColor.RED + "Usage: /hbl shadowsafe " + sub + " <radius>");
            return null;
        }
        try {
            return Math.max(0, Integer.parseInt(args[2]));
        } catch (NumberFormatException e) {
            s.sendMessage(ChatColor.RED + "Invalid radius: " + args[2]);
            return null;
        }
    }

    private void index(Conversion c) {
        conversions.put(posKey(c.world, c.x, c.y, c.z), c);
        byChunk.computeIfAbsent(chunkKey(c.world, c.x >> 4, c.z >> 4), k -> new ArrayList<>()).add(c);
    }

    private void unindex(Conversion c) {
        conversions.remove(posKey(c.world, c.x, c.y, c.z));
        String ck = chunkKey(c.world, c.x >> 4, c.z >> 4);
        List<Conversion> l = byChunk.get(ck);
        if (l != null) {
            l.remove(c);
            if (l.isEmpty()) {
                byChunk.remove(ck);
            }
        }
    }

    private static String posKey(UUID w, int x, int y, int z) {
        return w + ":" + x + ":" + y + ":" + z;
    }

    private static String chunkKey(UUID w, int cx, int cz) {
        return w + ":" + cx + ":" + cz;
    }

    private BlockData safeBlockData(String s) {
        try {
            return Bukkit.createBlockData(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String lc(Material m) {
        return m.name().toLowerCase(Locale.ROOT);
    }

    private static final class Conversion {
        final UUID world;
        final int x;
        final int y;
        final int z;
        final String original;
        final String proxy;
        UUID display;

        Conversion(UUID world, int x, int y, int z, String original, String proxy, UUID display) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.original = original;
            this.proxy = proxy;
            this.display = display;
        }
    }
}

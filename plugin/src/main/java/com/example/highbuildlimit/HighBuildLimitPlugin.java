package com.example.highbuildlimit;

import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * HighBuildLimit — companion/diagnostic plugin.
 *
 * This plugin does NOT change world height. Build height in Minecraft Java is
 * determined by the dimension type (data-driven), and the HighBuildLimit
 * datapack is what actually raises it. This plugin only:
 *   - reports each loaded world's real min/max build height, and
 *   - verifies whether the height matches the value you expect (config).
 */
public final class HighBuildLimitPlugin extends JavaPlugin {

    private ShadowSafe shadowSafe;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Always initialise so its commands work even if general logging is off below.
        shadowSafe = new ShadowSafe(this);
        shadowSafe.load();
        getServer().getPluginManager().registerEvents(shadowSafe, this);
        shadowSafe.verifyLoadedChunks();

        if (!getConfig().getBoolean("enabled", true)) {
            getLogger().info("Disabled via config (enabled: false). Commands still work.");
            return;
        }

        getLogger().info("HighBuildLimit companion/diagnostic plugin enabled.");
        getLogger().info("NOTE: this plugin does NOT change world height. The HighBuildLimit datapack does.");
        getLogger().info("The datapack keeps each dimension's vanilla min_y unchanged and only raises the top toward Y=2031.");
        logHeights();
    }

    @Override
    public void onDisable() {
        if (shadowSafe != null) {
            shadowSafe.shutdown();
        }
    }

    private void logHeights() {
        int target = getConfig().getInt("target-max-y", 2031);
        boolean debug = getConfig().getBoolean("debug", false);
        for (World w : getServer().getWorlds()) {
            int maxBuildY = w.getMaxHeight() - 1; // getMaxHeight() is the exclusive top boundary
            boolean raised = maxBuildY >= target;
            getLogger().info(String.format(
                    "World '%s' (%s): minY=%d, maxBuildY=%d %s",
                    w.getName(), w.getEnvironment(), w.getMinHeight(), maxBuildY,
                    raised ? "[raised - datapack active]" : "[vanilla/below target]"));
            if (debug) {
                getLogger().info(String.format(
                        "  getMinHeight()=%d getMaxHeight()=%d (exclusive top)",
                        w.getMinHeight(), w.getMaxHeight()));
            }
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("highbuildlimit")) {
            return false;
        }

        String sub = (args.length == 0) ? "info" : args[0].toLowerCase();
        switch (sub) {
            case "version":
                sender.sendMessage(ChatColor.GOLD + "HighBuildLimit "
                        + ChatColor.WHITE + "v" + getPluginMeta().getVersion());
                sender.sendMessage(ChatColor.GRAY
                        + "Companion plugin. Height is changed by the HighBuildLimit datapack, not this plugin.");
                return true;

            case "reload":
                reloadConfig();
                shadowSafe.load();
                sender.sendMessage(ChatColor.GREEN + "HighBuildLimit config reloaded.");
                return true;

            case "shadowsafe":
            case "shadow-safe":
                return shadowSafe.handleCommand(sender, args);

            case "info":
            default:
                sendInfo(sender);
                return true;
        }
    }

    private void sendInfo(CommandSender sender) {
        int target = getConfig().getInt("target-max-y", 2031);
        boolean enabled = getConfig().getBoolean("enabled", true);

        sender.sendMessage(ChatColor.GOLD + "=== HighBuildLimit ===");
        sender.sendMessage(ChatColor.GRAY + "Role: " + ChatColor.WHITE
                + "informational only (the datapack changes height, not this plugin).");
        sender.sendMessage(ChatColor.GRAY + "Config enabled: " + ChatColor.WHITE + enabled
                + ChatColor.GRAY + "   target-max-y: " + ChatColor.WHITE + target);
        sender.sendMessage(ChatColor.GRAY + "Minecraft hard limit: " + ChatColor.WHITE
                + "max placeable Y = 2031 (infinite is impossible).");
        sender.sendMessage(ChatColor.GRAY + "Datapack keeps each dimension's vanilla "
                + ChatColor.WHITE + "min_y" + ChatColor.GRAY + " unchanged; only the top is raised.");
        sender.sendMessage(ChatColor.GRAY + "Loaded worlds " + ChatColor.DARK_GRAY
                + "(minY should match vanilla: Overworld -64, End 0, Nether 0):");

        for (World w : getServer().getWorlds()) {
            int maxBuildY = w.getMaxHeight() - 1;
            boolean ok = maxBuildY >= target;
            sender.sendMessage(String.format(
                    "  " + ChatColor.WHITE + "%s " + ChatColor.GRAY + "(%s): minY=%d maxBuildY=%d %s",
                    w.getName(), w.getEnvironment(), w.getMinHeight(), maxBuildY,
                    ok ? ChatColor.GREEN + "[raised]" : ChatColor.YELLOW + "[vanilla/below target]"));
        }
    }
}

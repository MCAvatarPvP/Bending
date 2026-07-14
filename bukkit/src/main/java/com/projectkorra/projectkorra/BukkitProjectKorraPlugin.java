package com.projectkorra.projectkorra;

import com.projectkorra.projectkorra.command.BendingTabComplete;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.hooks.BetonQuestHook;
import com.projectkorra.projectkorra.hooks.WorldGuardFlag;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.bukkit.BukkitProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.command.Command;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.prediction.PaperPredictionServer;
import com.projectkorra.projectkorra.prediction.TempBlockPacketFilter;
import com.projectkorra.projectkorra.region.BukkitRegionProtectionBootstrap;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Bukkit/Paper entrypoint. Common runtime code lives in the common module.
 */
public final class BukkitProjectKorraPlugin extends JavaPlugin {
    private PaperPredictionServer prediction;
    private TempBlockPacketFilter tempBlockPacketFilter;

    private static CommandSender wrapSender(final org.bukkit.command.CommandSender sender) {
        if (sender instanceof Player player) {
            return BukkitMC.player(player);
        }
        return new BukkitConsoleSender(sender);
    }

    @Override
    public void onLoad() {
        // WorldGuard locks its flag registry before plugins are enabled. Registering
        // here also lets WorldGuard associate persisted values with ProjectKorra.
        if (getServer().getPluginManager().getPlugin("WorldGuard") != null) {
            WorldGuardFlag.registerBendingWorldGuardFlag(this);
        }
    }

    @Override
    public void onEnable() {
        Platform.install(new BukkitProjectKorraPlatform(this));
        ProjectKorra.initCommon();
        BukkitRegionProtectionBootstrap.registerBuiltIns();
        GeneralMethods.reloadPlugin(new BukkitConsoleSender(getServer().getConsoleSender()));
        registerCommands();
        Platform.events().registerListener(new PKListener(this));
        BetonQuestHook.register(this);
        if (getServer().getPluginManager().isPluginEnabled("packetevents")) {
            try {
                this.tempBlockPacketFilter = TempBlockPacketFilter.register();
                this.prediction = PaperPredictionServer.start(this);
                getLogger().info("Enabled prediction-owned TempBlock packet filtering.");
            } catch (Throwable failure) {
                if (this.tempBlockPacketFilter != null) this.tempBlockPacketFilter.stop();
                this.tempBlockPacketFilter = null;
                if (this.prediction != null) this.prediction.stop();
                this.prediction = null;
                getLogger().warning("Could not enable exact client prediction safely; using server authority: "
                        + failure.getMessage());
            }
        } else {
            // Fail closed: normal server-authoritative bending remains active,
            // but exact client prediction is not offered without the packet
            // ownership filter required to keep TempBlocks synchronized.
            getLogger().warning("PacketEvents is unavailable; exact client prediction is disabled to prevent TempBlock desyncs.");
        }
    }

    @Override
    public void onDisable() {
        if (this.tempBlockPacketFilter != null) {
            this.tempBlockPacketFilter.stop();
            this.tempBlockPacketFilter = null;
        }
        if (this.prediction != null) {
            this.prediction.stop();
            this.prediction = null;
        }
        Platform.scheduler().cancelAll();
    }

    private void registerCommands() {
        PluginCommand command = getCommand("projectkorra");
        if (command == null) {
            getLogger().warning("Unable to register /projectkorra command because it is missing from plugin.yml");
            return;
        }
        command.setExecutor((sender, ignored, label, args) ->
                Commands.dispatch(wrapSender(sender), label, args));
        command.setTabCompleter((sender, ignored, alias, args) ->
                new BendingTabComplete()
                        .onTabComplete(wrapSender(sender), new Command(), alias, args));
    }

    private record BukkitConsoleSender(org.bukkit.command.CommandSender value) implements CommandSender {
        @Override
        public String getName() {
            return value.getName();
        }

        @Override
        public void sendMessage(String message) {
            value.sendMessage(message);
        }

        @Override
        public boolean hasPermission(String permission) {
            return value.hasPermission(permission);
        }
    }
}

package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.OfflinePlayer;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class SprinkleCommand extends PKCommand {

    private final String invalidPlayer;
    private final String toggledOn;
    private final String toggledOff;

    public SprinkleCommand() {
        super("sprinkle", "/bending sprinkle <Player>", ConfigManager.languageConfig.get().getString("Commands.Sprinkle.Description"), new String[]{"sprinkle"});

        this.invalidPlayer = ConfigManager.languageConfig.get().getString("Commands.Sprinkle.InvalidPlayer");
        this.toggledOn = ConfigManager.languageConfig.get().getString("Commands.Sprinkle.ToggledOn");
        this.toggledOff = ConfigManager.languageConfig.get().getString("Commands.Sprinkle.ToggledOff");
    }

    @Override
    public void execute(CommandSender sender, List<String> args) {
        if (!this.correctLength(sender, args.size(), 0, 1)) return;

        if (args.size() == 1 && hasPermission(sender)) {
            this.toggleSprinkle(sender, args.get(0));
        } else if (args.size() == 2 && hasPermission(sender)) {
            this.setSprinkle(sender, args.get(0), args.get(1).equalsIgnoreCase("true"));
        } else if (args.size() == 0 && this.isPlayer(sender) && super.hasPermission(sender)) {
            this.toggleSprinkle(sender, sender.getName());
        }
    }

    private void toggleSprinkle(final CommandSender sender, final String target) {
        final OfflinePlayer player = Platform.players().getOfflinePlayer(target);
        if (!player.isOnline() && !player.hasPlayedBefore()) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidPlayer);
            return;
        }
        BendingPlayer.getOrLoadOfflineAsync(player).thenAccept(bPlayer -> {
            bPlayer.toggleSprinkle();
            if (bPlayer.isSprinkleEnabled())
                ChatUtil.sendBrandingMessage((Player) sender, ChatColor.GREEN + this.toggledOn);
            else ChatUtil.sendBrandingMessage((Player) sender, ChatColor.RED + this.toggledOff);
        });
    }

    private void setSprinkle(final CommandSender sender, final String target, final boolean sprinkle) {
        final OfflinePlayer player = Platform.players().getOfflinePlayer(target);
        if (!player.isOnline() && !player.hasPlayedBefore()) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidPlayer);
            return;
        }
        BendingPlayer.getOrLoadOfflineAsync(player).thenAccept(bPlayer -> {
            bPlayer.setSprinkle(sprinkle);
            if (bPlayer.isSprinkleEnabled())
                ChatUtil.sendBrandingMessage((Player) sender, ChatColor.GREEN + this.toggledOn);
            else ChatUtil.sendBrandingMessage((Player) sender, ChatColor.RED + this.toggledOff);
        });
    }

    @Override
    public boolean hasPermission(CommandSender sender) {
        if (!sender.hasPermission("bending.admin.sprinkle")) {
            ChatUtil.sendBrandingMessage(sender, super.noPermissionMessage);
            return false;
        }
        return true;
    }

    @Override
    protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
        if (args.size() >= 1 || !sender.hasPermission("bending.command.sprinkle")) {
            return new ArrayList<String>();
        }

        if (args.size() == 1) getOnlinePlayerNames(sender);
        else if (args.size() == 2) Arrays.asList("true", "false");
        return getOnlinePlayerNames(sender);
    }
}

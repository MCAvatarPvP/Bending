package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.object.GliderColor;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

public final class GliderColorCommand extends PKCommand {
    private final String invalidColor;
    private final String invalidPlayer;
    private final String changedColor;

    public GliderColorCommand() {
        super("gliderColor", "/bending gliderColor <Color>",
                ConfigManager.languageConfig.get().getString("Commands.GliderColor.Description"),
                new String[]{"glidercolor", "gcolor"});
        this.invalidColor = ConfigManager.languageConfig.get().getString("Commands.GliderColor.InvalidColor");
        this.invalidPlayer = ConfigManager.languageConfig.get().getString("Commands.GliderColor.PlayerNotFound");
        this.changedColor = ConfigManager.languageConfig.get().getString("Commands.GliderColor.ChangedColor");
    }

    @Override
    public void execute(final CommandSender sender, final List<String> args) {
        if (!this.correctLength(sender, args.size(), 1, 2)) return;
        if (args.size() == 1 && hasPermission(sender) && sender instanceof Player) {
            this.changeColor(sender, args.get(0), "");
        } else if (args.size() == 2 && sender.hasPermission("bending.admin.glidercolor")) {
            this.changeColor(sender, args.get(0), args.get(1));
        }
    }

    private void changeColor(final CommandSender sender, final String color, final String playerName) {
        final GliderColor selected = GliderColor.getColor(color);
        if (selected == null) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidColor.replace("{color}", color));
            return;
        }

        Player player = Platform.players().getPlayer(playerName);
        if (player == null && !playerName.isEmpty()) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidPlayer);
            return;
        }
        if (playerName.isEmpty()) player = (Player) sender;
        if (!player.isOnline() && !player.hasPlayedBefore()) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidPlayer);
            return;
        }

        final Player target = player;
        BendingPlayer.getOrLoadOfflineAsync(target).thenAccept(bPlayer -> {
            if (!"classic".equals(selected.getName())
                    && !sender.hasPermission("bending.glidercolor." + selected.getName())) {
                ChatUtil.sendBrandingMessage(sender, noPermissionMessage);
                return;
            }
            bPlayer.setGliderColor(selected);
            ChatUtil.sendBrandingMessage(sender,
                    ChatColor.GREEN + this.changedColor.replace("{color}", selected.getName()));
        });
    }

    @Override
    protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
        if (!sender.hasPermission("bending.command.glidercolor")) return new ArrayList<>();
        if (args.size() <= 1) return GliderColor.getColorNames();
        if (args.size() == 2) return getOnlinePlayerNames(sender);
        return new ArrayList<>();
    }
}

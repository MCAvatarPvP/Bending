package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.object.Style;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

public class StyleCommand extends PKCommand {

    private final String invalidStyle;
    private final String invalidPlayer;
    private final String changedStyle;

    public StyleCommand() {
        super("style", "/bending style <Style>", ConfigManager.languageConfig.get().getString("Commands.Style.Description"), new String[]{"style"});

        this.invalidStyle = ConfigManager.languageConfig.get().getString("Commands.Style.InvalidStyle");
        this.invalidPlayer = ConfigManager.languageConfig.get().getString("Commands.Style.PlayerNotFound");
        this.changedStyle = ConfigManager.languageConfig.get().getString("Commands.Style.ChangedStyle");
    }

    @Override
    public void execute(CommandSender sender, List<String> args) {
        if (!this.correctLength(sender, args.size(), 1, 2)) return;

        if (args.size() == 1 && hasPermission(sender) && sender instanceof Player) {
            this.chooseStyle(sender, args.get(0), "");
        } else if (args.size() == 2 && sender.hasPermission("bending.admin.style")) {
            this.chooseStyle(sender, args.get(0), args.get(1));
        }
    }

    private void chooseStyle(final CommandSender sender, String style, String player) {
        if (!Style.hasStyle(style)) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidStyle.replace("{style}", style));
            return;
        }

        Player p = Platform.players().getPlayer(player);
        if (p == null && !player.isEmpty()) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidPlayer);
            return;
        }

        if (player.isEmpty()) p = (Player) sender;

        if (!p.isOnline() && !p.hasPlayedBefore()) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidPlayer);
            return;
        }

        Style s = Style.getStyle(style);
        BendingPlayer.getOrLoadOfflineAsync(p).thenAccept(bPlayer -> {
            bPlayer.setStyle(s);
            ChatUtil.sendBrandingMessage(sender, ChatColor.GREEN + this.changedStyle.replace("{style}", s.getName()));
        });
    }

    @Override
    protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
        if (!sender.hasPermission("bending.command.style")) return new ArrayList<>();
        if (args.isEmpty()) return Style.getNames();
        else if (args.size() == 1) return getOnlinePlayerNames(sender);

        return new ArrayList<>();
    }
}

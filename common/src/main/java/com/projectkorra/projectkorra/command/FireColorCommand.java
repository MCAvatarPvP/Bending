package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.object.CosmeticColor;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

public class FireColorCommand extends PKCommand {

    private final String invalidColor;
    private final String invalidPlayer;
    private final String changedColor;

    public FireColorCommand() {
        super("fireColor", "/bending fireColor <Color>", ConfigManager.languageConfig.get().getString("Commands.FireColor.Description"), new String[]{"firecolor"});

        this.invalidColor = ConfigManager.languageConfig.get().getString("Commands.FireColor.InvalidColor");
        this.invalidPlayer = ConfigManager.languageConfig.get().getString("Commands.FireColor.PlayerNotFound");
        this.changedColor = ConfigManager.languageConfig.get().getString("Commands.FireColor.ChangedColor");
    }

    @Override
    public void execute(CommandSender sender, List<String> args) {
        if (!this.correctLength(sender, args.size(), 1, 2)) return;

        if (args.size() == 1 && hasPermission(sender) && sender instanceof Player) {
            this.changeColor(sender, args.get(0), "");
        } else if (args.size() == 2 && sender.hasPermission("bending.admin.firecolor")) {
            this.changeColor(sender, args.get(0), args.get(1));
        }
    }

    private void changeColor(final CommandSender sender, String color, String player) {
        if (!CosmeticColor.hasFireColor(color)) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidColor.replace("{color}", color));
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

        CosmeticColor clr = CosmeticColor.getFireColor(color);
        BendingPlayer.getOrLoadOfflineAsync(p).thenAccept(bPlayer -> {
            if (!color.equalsIgnoreCase("none") && !sender.hasPermission("bending.firecolor." + clr.getName())) {
                ChatUtil.sendBrandingMessage(sender, noPermissionMessage);
                return;
            }
            bPlayer.setFireColor(clr);
            ChatUtil.sendBrandingMessage(sender, ChatColor.GREEN + this.changedColor.replace("{color}", clr.getName()));
        });
    }

    @Override
    protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
        if (!sender.hasPermission("bending.command.firecolor")) return new ArrayList<>();
        if (args.isEmpty()) return CosmeticColor.getFireNames();
        else if (args.size() == 1) return getOnlinePlayerNames(sender);

        return new ArrayList<>();
    }
}

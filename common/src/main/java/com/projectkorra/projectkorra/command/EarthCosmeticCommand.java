package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.object.EarthCosmetic;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

public class EarthCosmeticCommand extends PKCommand {

    private final String invalidCosmetic;
    private final String invalidPlayer;
    private final String changedCosmetic;

    public EarthCosmeticCommand() {
        super("earthCosmetic", "/bending earthCosmetic <Color>", ConfigManager.languageConfig.get().getString("Commands.EarthCosmetic.Description"), new String[]{"earthcosmetic", "ecosmetic", "ecos", "ec"});

        this.invalidCosmetic = ConfigManager.languageConfig.get().getString("Commands.EarthCosmetic.InvalidCosmetic");
        this.invalidPlayer = ConfigManager.languageConfig.get().getString("Commands.EarthCosmetic.PlayerNotFound");
        this.changedCosmetic = ConfigManager.languageConfig.get().getString("Commands.EarthCosmetic.ChangedCosmetic");
    }

    @Override
    public void execute(CommandSender sender, List<String> args) {
        if (!this.correctLength(sender, args.size(), 0, 2)) return;

        if (!hasPermission(sender)) return;

        if (args.size() == 0 && sender instanceof Player) {
            this.changeColor(sender, "none", "");
        } else if (args.size() == 1 && sender instanceof Player) {
            this.changeColor(sender, args.get(0), "");
        } else if (args.size() == 2 && sender.hasPermission("bending.admin.earthcosmetic")) {
            this.changeColor(sender, args.get(0), args.get(1));
        }
    }

    private void changeColor(final CommandSender sender, String cosmetic, String player) {
        if (!EarthCosmetic.hasCosmetic(cosmetic)) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidCosmetic.replace("{cosmetic}", cosmetic));
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

        EarthCosmetic cos = EarthCosmetic.getCosmetic(cosmetic);
        BendingPlayer.getOrLoadOfflineAsync(p).thenAccept(bPlayer -> {
            if (!cosmetic.equalsIgnoreCase("none") && !sender.hasPermission("bending.earthcosmetic." + cos.getName())) {
                ChatUtil.sendBrandingMessage(sender, noPermissionMessage);
                return;
            }
            bPlayer.setEarthCosmetic(cos);
            ChatUtil.sendBrandingMessage(sender, ChatColor.GREEN + this.changedCosmetic.replace("{cosmetic}", cos.getName()));
        });
    }

    @Override
    protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
        if (!sender.hasPermission("bending.command.earthcosmetic")) return new ArrayList<>();
        if (args.isEmpty()) return EarthCosmetic.getCosmeticNames();
        else if (args.size() == 1) return getOnlinePlayerNames(sender);

        return new ArrayList<>();
    }
}

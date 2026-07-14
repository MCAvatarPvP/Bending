package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.object.WaterCosmetic;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.ArrayList;
import java.util.List;

public class WaterCosmeticCommand extends PKCommand {

    private final String invalidCosmetic;
    private final String invalidPlayer;
    private final String changedCosmetic;

    public WaterCosmeticCommand() {
        super("waterCosmetic", "/bending waterCosmetic <Color>", ConfigManager.languageConfig.get().getString("Commands.WaterCosmetic.Description"), new String[]{"watercosmetic", "wcosmetic", "wcos", "watercos"});

        this.invalidCosmetic = ConfigManager.languageConfig.get().getString("Commands.WaterCosmetic.InvalidCosmetic");
        this.invalidPlayer = ConfigManager.languageConfig.get().getString("Commands.WaterCosmetic.PlayerNotFound");
        this.changedCosmetic = ConfigManager.languageConfig.get().getString("Commands.WaterCosmetic.ChangedCosmetic");
    }

    @Override
    public void execute(final CommandSender sender, final List<String> args) {
        if (!this.correctLength(sender, args.size(), 0, 2)) {
            return;
        }

        if (!hasPermission(sender)) {
            return;
        }

        if (args.size() == 0 && sender instanceof Player) {
            this.changeCosmetic(sender, "none", "");
        } else if (args.size() == 1 && sender instanceof Player) {
            this.changeCosmetic(sender, args.get(0), "");
        } else if (args.size() == 2 && sender.hasPermission("bending.admin.watercosmetic")) {
            this.changeCosmetic(sender, args.get(0), args.get(1));
        }
    }

    private void changeCosmetic(final CommandSender sender, final String cosmetic, final String player) {
        if (!WaterCosmetic.hasCosmetic(cosmetic)) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidCosmetic.replace("{cosmetic}", cosmetic));
            return;
        }

        Player p = Platform.players().getPlayer(player);
        if (p == null && !player.isEmpty()) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidPlayer);
            return;
        }

        if (player.isEmpty()) {
            p = (Player) sender;
        }

        if (!p.isOnline() && !p.hasPlayedBefore()) {
            ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidPlayer);
            return;
        }

        final WaterCosmetic cos = WaterCosmetic.getCosmetic(cosmetic);
        BendingPlayer.getOrLoadOfflineAsync(p).thenAccept(bPlayer -> {
            if (!cosmetic.equalsIgnoreCase("none") && !sender.hasPermission("bending.watercosmetics." + cos.getName())) {
                ChatUtil.sendBrandingMessage(sender, noPermissionMessage);
                return;
            }
            bPlayer.setWaterCosmetic(cos);
            ChatUtil.sendBrandingMessage(sender, ChatColor.GREEN + this.changedCosmetic.replace("{cosmetic}", cos.getName()));
        });
    }

    @Override
    protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
        if (!sender.hasPermission("bending.command.watercosmetic")) {
            return new ArrayList<>();
        }
        if (args.size() <= 1) {
            return WaterCosmetic.getCosmeticNames();
        } else if (args.size() == 2) {
            return getOnlinePlayerNames(sender);
        }

        return new ArrayList<>();
    }
}

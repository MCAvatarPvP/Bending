package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.List;

public class OldScooterCommand extends PKCommand {

    private final String toggledOn;
    private final String toggledOff;

    public OldScooterCommand() {
        super("oldscooter", "/bending oldscooter",
                ConfigManager.languageConfig.get().getString("Commands.OldScooter.Description"),
                new String[]{"oldscooter"});
        this.toggledOn = ConfigManager.languageConfig.get().getString("Commands.OldScooter.ToggledOn");
        this.toggledOff = ConfigManager.languageConfig.get().getString("Commands.OldScooter.ToggledOff");
    }

    @Override
    public void execute(final CommandSender sender, final List<String> args) {
        if (!this.hasPermission(sender) || !this.isPlayer(sender) || !this.correctLength(sender, args.size(), 0, 0)) {
            return;
        }

        final Player player = (Player) sender;
        final BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
        if (bPlayer == null) {
            return;
        }

        bPlayer.toggleOldScooter();
        final boolean enabled = bPlayer.isOldScooterEnabled();
        ChatUtil.sendBrandingMessage(player, (enabled ? ChatColor.GREEN : ChatColor.YELLOW)
                + (enabled ? this.toggledOn : this.toggledOff));
    }
}

package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.ChatColor;
import com.projectkorra.projectkorra.platform.mc.command.CommandSender;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.util.ActionBarStatusManager;
import com.projectkorra.projectkorra.util.ChatUtil;

import java.util.List;

public class DetailedActionBarCommand extends PKCommand {

    private final String toggledOn;
    private final String toggledOff;

    public DetailedActionBarCommand() {
        super("detailedactionbar", "/bending detailedActionBar",
                ConfigManager.languageConfig.get().getString("Commands.DetailedActionBar.Description"),
                new String[]{"detailedactionbar", "detailedbar", "dab"});
        this.toggledOn = ConfigManager.languageConfig.get().getString("Commands.DetailedActionBar.ToggledOn");
        this.toggledOff = ConfigManager.languageConfig.get().getString("Commands.DetailedActionBar.ToggledOff");
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

        bPlayer.toggleDetailedActionBar();
        final boolean enabled = bPlayer.isDetailedActionBarEnabled();
        ChatUtil.sendBrandingMessage(player, (enabled ? ChatColor.GREEN : ChatColor.RED)
                + (enabled ? this.toggledOn : this.toggledOff));
        ActionBarStatusManager.display(player);
    }
}

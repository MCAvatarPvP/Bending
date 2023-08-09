package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.util.ChatUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class SourceHolesCommand extends PKCommand {

	private final String invalidPlayer;
	private final String toggledOn;
	private final String toggledOff;

	public SourceHolesCommand() {
		super("sourceholes", "/bending sourceHoles <Player>", ConfigManager.languageConfig.get().getString("Commands.SourceHoles.Description"), new String[] { "sourceholes", "sh", "holes" });

		this.invalidPlayer = ConfigManager.languageConfig.get().getString("Commands.SourceHoles.InvalidPlayer");
		this.toggledOn = ConfigManager.languageConfig.get().getString("Commands.SourceHoles.ToggledOn");
		this.toggledOff = ConfigManager.languageConfig.get().getString("Commands.SourceHoles.ToggledOff");
	}

	@Override
	public void execute(CommandSender sender, List<String> args) {
		if (!this.correctLength(sender, args.size(), 0, 1)) return;

		if (args.size() == 1 && hasPermission(sender)) {
			this.toggleHoles(sender, args.get(0));
		} else if (args.size() == 0 && this.isPlayer(sender) && super.hasPermission(sender)) {
			this.toggleHoles(sender, sender.getName());
		}
	}

	private void toggleHoles(final CommandSender sender, final String target) {
		final OfflinePlayer player = Bukkit.getOfflinePlayer(target);
		if (!player.isOnline() && !player.hasPlayedBefore()) {
			ChatUtil.sendBrandingMessage(sender, ChatColor.RED + this.invalidPlayer);
			return;
		}
		BendingPlayer.getOrLoadOfflineAsync(player).thenAccept(bPlayer -> {
			bPlayer.toggleSourceHoles();
			if (bPlayer.areSourceHolesOn()) ChatUtil.sendBrandingMessage((Player) sender, ChatColor.GREEN + this.toggledOn);
			else ChatUtil.sendBrandingMessage((Player) sender, ChatColor.RED + this.toggledOff);
		});
	}

	@Override
	public boolean hasPermission(CommandSender sender) {
		if (!sender.hasPermission("bending.admin.sourceholes")) {
			ChatUtil.sendBrandingMessage(sender, super.noPermissionMessage);
			return false;
		}
		return true;
	}

	@Override
	protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
		if (args.size() >= 1 || !sender.hasPermission("bending.command.sourceholes")) {
			return new ArrayList<String>();
		}
		return getOnlinePlayerNames(sender);
	}
}

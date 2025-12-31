package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.util.ChatUtil;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class ViewDistanceCommand extends PKCommand {

	private final String checkDistance;
	private final String invalidDistance;
	private final String changedDistance;

	public ViewDistanceCommand() {
		super("viewDistance", "/bending viewDistance <distance in blocks>", ConfigManager.languageConfig.get().getString("Commands.ViewDistance.Description"), new String[] { "viewdistance" });

		this.checkDistance = ConfigManager.languageConfig.get().getString("Commands.ViewDistance.CheckDistance");
		this.invalidDistance = ConfigManager.languageConfig.get().getString("Commands.ViewDistance.InvalidDistance");
		this.changedDistance = ConfigManager.languageConfig.get().getString("Commands.ViewDistance.ChangedDistance");
	}

	@Override
	public void execute(final CommandSender sender, final List<String> args) {
		if (!this.correctLength(sender, args.size(), 0, 1) ||
				!this.hasPermission(sender) ||
				!(sender instanceof Player player))
			return;

		if (args.isEmpty()) {
			this.checkDistance(player);
		} else if (args.size() == 1) {
			this.changeDistance(player, args.getFirst());
		}
	}

	private void checkDistance(final Player player) {
		int viewDistance = BendingPlayer.getBendingPlayer(player).getViewDistance();
		ChatUtil.sendBrandingMessage(player, ChatColor.GREEN + this.checkDistance.replace("{distance}", "" + viewDistance));
	}

	private void changeDistance(final Player player, final String distance) {
		int viewDistance = distance.equalsIgnoreCase("default") ? 256 : parseDistance(distance);

		if (viewDistance == -1) {
			ChatUtil.sendBrandingMessage(player, ChatColor.RED + this.invalidDistance.replace("{distance}", distance));
			return;
		}

		BendingPlayer.getBendingPlayer(player).setViewDistance(viewDistance);
		ChatUtil.sendBrandingMessage(player, ChatColor.GREEN + this.changedDistance.replace("{distance}", "" + viewDistance));
	}

	private int parseDistance(final String distance) {
		try {
			return Math.max(0, Math.min(256, Integer.parseInt(distance)));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	@Override
	protected List<String> getTabCompletion(final CommandSender sender, final List<String> args) {
		if (!sender.hasPermission("bending.command.viewdistance")) return new ArrayList<>();
		if (args.isEmpty()) return List.of("default");

		return new ArrayList<>();
	}
}

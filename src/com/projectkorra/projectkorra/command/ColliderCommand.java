package com.projectkorra.projectkorra.command;

import com.projectkorra.projectkorra.util.ChatUtil;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ColliderCommand extends PKCommand {

	private static final Set<Player> players = new HashSet<>();

	public ColliderCommand() {
		super("collider", "/bending collider", "Command to toggle collider view", new String[] { "collider", "coll" });
	}

	@Override
	public void execute(CommandSender sender, List<String> args) {
		if (!hasPermission(sender)) return;
		if (!(sender instanceof Player)) return;

		Player player = (Player) sender;
		if (players.contains(player)) {
			players.remove(player);
			ChatUtil.sendBrandingMessage(player, "Disabled Collider display");
		} else {
			players.add(player);
			ChatUtil.sendBrandingMessage(player, "Enabled Collider display (Only you're seeing the colliders)");
		}
	}

	public static Set<Player> getPlayers() {
		return players;
	}
}
package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;

public class FallHandler {

	public static Map<Player, Boolean> affectedEntitiesByPush = new HashMap<>();

	public static void stopFall(Player player) {
		affectedEntitiesByPush.put(player, false);
		ProjectKorra.plugin.getServer().getScheduler().runTaskLater(ProjectKorra.plugin, () -> {
			if (affectedEntitiesByPush.containsKey(player) && !affectedEntitiesByPush.get(player)) {
				affectedEntitiesByPush.remove(player);
			}
		}, 20);
	}

	public static void removePlayer(Player player) {
		affectedEntitiesByPush.remove(player);
	}

	public static void move(Player player) {
		if (!affectedEntitiesByPush.containsKey(player)) return;
		boolean b = affectedEntitiesByPush.get(player);
		if (b) return;

		if (!GeneralMethods.isOnGround(player)) affectedEntitiesByPush.put(player, true);
	}

}
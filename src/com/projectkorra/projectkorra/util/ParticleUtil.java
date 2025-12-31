package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.BendingPlayer;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.Collection;

public class ParticleUtil {

	public static void spawn(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ) {
		spawn(location.getWorld().getPlayers(), particle, location, count, offsetX, offsetY, offsetZ, 1.0);
	}

	public static void spawn(Collection<Player> receivers, Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ) {
		spawn(receivers, particle, location, count, offsetX, offsetY, offsetZ, 1.0);
	}

	public static void spawn(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra) {
		spawn(location.getWorld().getPlayers(), particle, location, count, offsetX, offsetY, offsetZ, extra, null);
	}

	public static void spawn(Collection<Player> receivers, Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra) {
		spawn(receivers, particle, location, count, offsetX, offsetY, offsetZ, extra, null);
	}

	public static <T> void spawn(Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra, T data) {
		spawn(location.getWorld().getPlayers(), particle, location, count, offsetX, offsetY, offsetZ, extra, data);
	}

	public static <T> void spawn(Collection<Player> receivers, Particle particle, Location location, int count, double offsetX, double offsetY, double offsetZ, double extra, T data) {
		for (Player player : receivers) {
			if (player.getWorld() != location.getWorld()) continue;
			BendingPlayer bPlayer = BendingPlayer.getBendingPlayer(player);
			if (bPlayer != null && !isInViewDistance(player, location, bPlayer.getViewDistanceSqrt())) continue;

			player.spawnParticle(particle, location, count, offsetX, offsetY, offsetZ, extra, data, true);
		}
	}

	private static boolean isInViewDistance(Player player, Location location, double viewDistanceSqrt) {
		return player.getLocation().distanceSquared(location) < viewDistanceSqrt;
	}

}
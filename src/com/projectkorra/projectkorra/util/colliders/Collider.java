package com.projectkorra.projectkorra.util.colliders;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.function.Predicate;

public interface Collider {

	Location getCenter();

	Collider expand(double size);

	boolean contains(Location point);

	boolean intersects(Collider collider);

	Location getNearestPoint(Location point);

	default Collection<Entity> getEntities() {
		return getEntities(null);
	}

	Collection<Entity> getEntities(Predicate<Entity> filter);

	Collection<Block> getBlocks(Predicate<Block> filter);

	void display(Collection<Player> players);

	Particle getDisplayParticle();

	default void spawnParticle(Collection<Player> players, Location location) {
		if (players == null || players.isEmpty()) {
			location.getWorld().spawnParticle(getDisplayParticle(), location, 0, 0, 99999999, 0, 99999999, null, true);
			return;
		}

		for (Player player : players) {
			if (player.getWorld() != location.getWorld()) continue;

			player.spawnParticle(getDisplayParticle(), location, 0, 0, 99999999, 0, 99999999, null);
		}
	}

	AABB toAABB();

}
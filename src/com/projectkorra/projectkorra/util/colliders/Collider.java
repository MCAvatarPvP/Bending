package com.projectkorra.projectkorra.util.colliders;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Collection;
import java.util.function.Predicate;

public interface Collider {

	Location getCenter();

	boolean contains(Location point);

	boolean intersects(Collider collider);

	Location getNearestPoint(Location point);

	default Collection<Entity> getEntities() {
		return getEntities(null);
	}

	Collection<Entity> getEntities(Predicate<Entity> filter);

	AABB toAABB();

}
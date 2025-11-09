package com.projectkorra.projectkorra.util.colliders;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

import java.util.Collection;
import java.util.function.Predicate;

public class Sphere implements Collider {

	private final Location center;
	private final double radius;

	public Sphere(Location center, double radius) {
		this.center = center;
		this.radius = radius;
	}

	public double getRadius() {
		return radius;
	}

	@Override
	public Location getCenter() {
		return center.clone();
	}

	@Override
	public boolean contains(Location point) {
		return center.distanceSquared(point) <= radius * radius;
	}

	@Override
	public boolean intersects(Collider collider) {
		if (collider instanceof Sphere) {
			Sphere other = (Sphere) collider;
			double sumRadius = radius + other.getRadius();
			return center.distanceSquared(other.getCenter()) <= sumRadius * sumRadius;
		} else if (collider instanceof AABB) {
			return contains(collider.getNearestPoint(center));
		}
		return false;
	}

	@Override
	public Location getNearestPoint(Location point) {
		return getCenter().add(point.clone().subtract(center));
	}

	@Override
	public Collection<Entity> getEntities(Predicate<Entity> filter) {
		Predicate<Entity> sphereIntersetion = entity -> intersects(new AABB(entity.getWorld(), entity.getBoundingBox()));
		return toAABB().getEntities(sphereIntersetion.and(filter));
	}

	@Override
	public AABB toAABB() {
		return new AABB(center, radius);
	}
}

package com.projectkorra.projectkorra.util.colliders;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.util.BoundingBox;

import java.util.Collection;
import java.util.function.Predicate;

public class AABB implements Collider {

	private final Location min;
	private final Location max;

	public AABB(World world, BoundingBox boundingBox) {
		this(boundingBox.getMin().toLocation(world), boundingBox.getMax().toLocation(world));
	}

	public AABB(Location center, double radius) {
		this(center, radius, radius, radius);
	}

	public AABB(Location center, double x, double y, double z) {
		this(center.clone().subtract(x, y, z), center.clone().add(x, y, z));
	}

	public AABB(Location min, Location max) {
		this.min = new Location(
				min.getWorld(),
				Math.min(min.getX(), max.getX()),
				Math.min(min.getY(), max.getY()),
				Math.min(min.getZ(), max.getZ())
		);
		this.max = new Location(
				min.getWorld(),
				Math.max(min.getX(), max.getX()),
				Math.max(min.getY(), max.getY()),
				Math.max(min.getZ(), max.getZ())
		);
	}

	public Location getMin() {
		return min.clone();
	}

	public Location getMax() {
		return max.clone();
	}

	@Override
	public Location getCenter() {
		return getMin().add(getMax().subtract(min).multiply(0.5));
	}

	@Override
	public boolean contains(Location point) {
		return point.getX() >= min.getX() && point.getX() <= max.getX() &&
				point.getY() >= min.getY() && point.getY() <= max.getY() &&
				point.getZ() >= min.getZ() && point.getZ() <= max.getZ();
	}

	@Override
	public boolean intersects(Collider collider) {
		if (collider instanceof AABB) {
			final AABB other = (AABB) collider;
			return this.min.getX() < other.max.getX() && this.max.getX() > other.min.getX() &&
					this.min.getY() < other.max.getY() && this.max.getY() > other.min.getY() &&
					this.min.getZ() < other.max.getZ() && this.max.getZ() > other.min.getZ();
		} else if (collider instanceof Sphere) {
			final Sphere sphere = (Sphere) collider;
			return sphere.intersects(this);
		}
		return false;
	}

	@Override
	public Location getNearestPoint(Location point) {
		return new Location(
				min.getWorld(),
				Math.max(min.getX(), Math.min(point.getX(), max.getX())),
				Math.max(min.getY(), Math.min(point.getY(), max.getY())),
				Math.max(min.getZ(), Math.min(point.getZ(), max.getZ()))
		);
	}

	@Override
	public Collection<Entity> getEntities(Predicate<Entity> filter) {
		return min.getWorld().getNearbyEntities(toBoundingBox(), filter);
	}

	@Override
	public AABB toAABB() {
		return new AABB(min, max);
	}

	public BoundingBox toBoundingBox() {
		return BoundingBox.of(min, max);
	}
}
package com.projectkorra.projectkorra.util.colliders;

import com.projectkorra.projectkorra.command.ColliderCommand;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class Ray implements Collider {

	private final Location location;
	private final Vector direction;
	private final double length;

	public Ray(Location location, Vector direction, double length) {
		this.location = location.clone();
		this.direction = direction.clone();
		this.length = length;

		Set<Player> players = ColliderCommand.getPlayers();
		if (!players.isEmpty()) display(players);
	}

	public Optional<Double> hitDistance(Collider collider) {
		if (collider instanceof AABB) {
			AABB aabb = (AABB) collider;
			if (location.getWorld() != aabb.getMin().getWorld()) {
				return Optional.empty();
			}

			Vector invDir = new Vector(1, 1, 1).divide(direction);
			boolean signX = invDir.getX() < 0;
			boolean signY = invDir.getY() < 0;
			boolean signZ = invDir.getZ() < 0;

			Location min = aabb.getMin();
			Location max = aabb.getMax();
			Vector bounds = new Vector(signX ? max.getX() : min.getX(), signY ? max.getY() : min.getY(), signZ ? max.getZ() : min.getZ());
			Vector boundsMax = new Vector(signX ? min.getX() : max.getX(), signY ? min.getY() : max.getY(), signZ ? min.getZ() : max.getZ());

			Vector t1 = bounds.clone().subtract(location.toVector()).multiply(invDir);
			Vector t2 = boundsMax.clone().subtract(location.toVector()).multiply(invDir);

			Vector tMin = new Vector(Math.min(t1.getX(), t2.getX()), Math.min(t1.getY(), t2.getY()), Math.min(t1.getZ(), t2.getZ()));
			Vector tMax = new Vector(Math.max(t1.getX(), t2.getX()), Math.max(t1.getY(), t2.getY()), Math.max(t1.getZ(), t2.getZ()));

			double maxEntry = Math.max(Math.max(tMin.getX(), tMin.getY()), tMin.getZ());
			double minExit = Math.min(Math.min(tMax.getX(), tMax.getY()), tMax.getZ());

			if (maxEntry > minExit || minExit < 0 || maxEntry >= length) {
				return Optional.empty();
			}

			return Optional.of(maxEntry);
		} else if (collider instanceof Sphere) {
			Sphere sphere = (Sphere) collider;
			if (location.getWorld() != sphere.getCenter().getWorld()) {
				return Optional.empty();
			}

			Vector oc = getLocation().toVector().subtract(sphere.getCenter().toVector());
			double directionToSphere = direction.dot(oc);
			if (directionToSphere > 0) {
				return Optional.empty();
			}

			double b = 2.0 * directionToSphere;
			double c = oc.dot(oc) - sphere.getRadius() * sphere.getRadius();

			double discriminant = b * b - 4.0 * c;

			if (discriminant < 0) {
				return Optional.empty();
			}

			double sqrtDis = Math.sqrt(discriminant);
			double t1 = (-b - sqrtDis) / 2;
			if (t1 > 0 && t1 <= length) {
				return Optional.of(t1);
			}
		}

		return Optional.empty();
	}

	public Location hitLocation(Collider collider) {
		Optional<Double> hitDistance = hitDistance(collider);
		return hitDistance.map(dist -> getLocation().add(getDirection().multiply(dist))).orElse(null);
	}

	public Location getLocation() {
		return location.clone();
	}

	public Vector getDirection() {
		return direction.clone();
	}

	public double getLength() {
		return length;
	}

	@Override
	public Location getCenter() {
		return getLocation().add(getDirection().multiply(length * 0.5));
	}

	@Override
	public Ray expand(double size) {
		return new Ray(location, direction, length + size);
	}

	@Override
	public boolean contains(Location point) {
		return hitDistance(new Sphere(point, 0)).isPresent();
	}

	@Override
	public boolean intersects(Collider collider) {
		return hitDistance(collider).isPresent();
	}

	@Override
	public Location getNearestPoint(Location point) {
		return null;
	}

	@Override
	public Collection<Entity> getEntities(Predicate<Entity> filter) {
		Predicate<Entity> rayIntersetion = entity -> intersects(new AABB(entity.getWorld(), entity.getBoundingBox()));
		return toAABB().getEntities(rayIntersetion.and(filter));
	}

	@Override
	public Collection<Block> getBlocks(Predicate<Block> filter) {
		Predicate<Block> rayIntersetion = block -> intersects(new AABB(block.getWorld(), block.getBoundingBox()));
		return toAABB().getBlocks(rayIntersetion.and(filter));
	}

	@Override
	public void display(Collection<Player> players) {
		Location loc = getLocation();
		Vector dir = getDirection().multiply(length * 0.1);

		for (int i = 0; i < 10; i++) {
			spawnParticle(players, loc);
			loc.add(dir);
		}
	}

	@Override
	public Particle getDisplayParticle() {
		return Particle.SCRAPE;
	}

	@Override
	public AABB toAABB() {
		Location max = getLocation().add(getDirection().multiply(length));
		return new AABB(location, max);
	}
}
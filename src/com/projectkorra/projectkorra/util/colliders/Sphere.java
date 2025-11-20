package com.projectkorra.projectkorra.util.colliders;

import com.projectkorra.projectkorra.command.ColliderCommand;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.Collection;
import java.util.Set;
import java.util.function.Predicate;

public class Sphere implements Collider {

	private final Location center;
	private final double radius;

	public Sphere(Location center, double radius) {
		this.center = center;
		this.radius = radius;

		Set<Player> players = ColliderCommand.getPlayers();
		if (!players.isEmpty()) display(players);
	}

	public double getRadius() {
		return radius;
	}

	@Override
	public Location getCenter() {
		return center.clone();
	}

	@Override
	public Collider expand(double size) {
		return new Sphere(center, radius + size);
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
	public Collection<Block> getBlocks(Predicate<Block> filter) {
		Predicate<Block> sphereIntersetion = block -> intersects(new AABB(block.getWorld(), block.getBoundingBox()));
		return toAABB().getBlocks(sphereIntersetion.and(filter));
	}

	@Override
	public void display(Collection<Player> players) {
		for (double theta = 0; theta <= 180; theta += 22.5) {
			double rtheta = Math.toRadians(theta);
			for (double phi = 0; phi < 360; phi += 22.5) {
				double rphi = Math.toRadians(phi);

				double x = radius * Math.cos(rphi) * Math.sin(rtheta);
				double y = radius * Math.cos(rtheta);
				double z = radius * Math.sin(rphi) * Math.sin(rtheta);
				Location c = getCenter().add(new Vector(x, y, z));
				spawnParticle(players, c);
			}
		}
	}

	@Override
	public Particle getDisplayParticle() {
		return Particle.WAX_ON;
	}

	@Override
	public AABB toAABB() {
		return new AABB(center, radius);
	}
}

package com.projectkorra.projectkorra.util.colliders;

import com.projectkorra.projectkorra.command.ColliderCommand;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.Collection;
import java.util.HashSet;
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
        Set<Block> blocks = new HashSet<>();
        World world = location.getWorld();

        double dirX = direction.getX();
        double dirY = direction.getY();
        double dirZ = direction.getZ();

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();

        int stepX = dirX > 0 ? 1 : -1;
        int stepY = dirY > 0 ? 1 : -1;
        int stepZ = dirZ > 0 ? 1 : -1;

        double tDeltaX = dirX == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dirX);
        double tDeltaY = dirY == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dirY);
        double tDeltaZ = dirZ == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dirZ);

        double nextX = stepX > 0 ? (blockX + 1 - x) : (x - blockX);
        double nextY = stepY > 0 ? (blockY + 1 - y) : (y - blockY);
        double nextZ = stepZ > 0 ? (blockZ + 1 - z) : (z - blockZ);

        double tMaxX = dirX == 0 ? Double.MAX_VALUE : nextX / Math.abs(dirX);
        double tMaxY = dirY == 0 ? Double.MAX_VALUE : nextY / Math.abs(dirY);
        double tMaxZ = dirZ == 0 ? Double.MAX_VALUE : nextZ / Math.abs(dirZ);

        double traveled = 0.0;

        while (traveled <= length) {
            Block block = world.getBlockAt(blockX, blockY, blockZ);
            if (filter == null || filter.test(block))
                blocks.add(block);

            if (tMaxX < tMaxY) {
                if (tMaxX < tMaxZ) {
                    blockX += stepX;
                    traveled = tMaxX;
                    tMaxX += tDeltaX;
                } else {
                    blockZ += stepZ;
                    traveled = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            } else {
                if (tMaxY < tMaxZ) {
                    blockY += stepY;
                    traveled = tMaxY;
                    tMaxY += tDeltaY;
                } else {
                    blockZ += stepZ;
                    traveled = tMaxZ;
                    tMaxZ += tDeltaZ;
                }
            }
        }
        return blocks;
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
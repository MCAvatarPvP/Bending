package com.projectkorra.projectkorra.util.colliders;

import com.projectkorra.projectkorra.command.ColliderCommand;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.NumberConversions;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.*;
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

    public AABB(Location center, double xz, double y) {
        this(center, xz, y, xz);
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

        Set<Player> players = ColliderCommand.getPlayers();
        if (!players.isEmpty()) display(players);
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
    public AABB expand(double size) {
        Vector vector = new Vector(size, size, size);
        return new AABB(getMin().subtract(vector), getMax().add(vector));
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
        BoundingBox box = toBoundingBox();
        if (box == null) return Collections.emptyList();
        return min.getWorld().getNearbyEntities(box, filter);
    }

    @Override
    public Collection<Block> getBlocks(Predicate<Block> filter) {
        Set<Block> blocks = new HashSet<>();
        World world = min.getWorld();
        for (int x = min.getBlockX(); x <= max.getBlockX(); x++) {
            for (int y = min.getBlockY(); y <= max.getBlockY(); y++) {
                for (int z = min.getBlockZ(); z <= max.getBlockZ(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!intersects(new AABB(world, block.getBoundingBox())) || (filter != null && !filter.test(block))) {
                        continue;
                    }

                    blocks.add(block);
                }
            }
        }
        return blocks;
    }

    @Override
    public void display(Collection<Player> players) {
        drawLines(players, AxisOrder.XYZ);
        drawLines(players, AxisOrder.YXZ);
        drawLines(players, AxisOrder.ZYX);
    }

    @Override
    public Particle getDisplayParticle() {
        return Particle.WAX_OFF;
    }

    private void drawLines(Collection<Player> players, AxisOrder axisOrder) {
        Vector mn = adjustVector(min.toVector(), axisOrder);
        Vector mx = adjustVector(max.toVector(), axisOrder);
        double step = (mx.getX() - mn.getX()) * 0.25;
        if (step <= 0) return;

        for (double x = mn.getX(); x <= mx.getX(); x += step)
            for (double y : Arrays.asList(mn.getY(), mx.getY()))
                for (double z : Arrays.asList(mn.getZ(), mx.getZ())) {
                    Vector xyz = adjustVector(new Vector(x, y, z), axisOrder);

                    spawnParticle(players, xyz.toLocation(min.getWorld()));
                }
    }

    private Vector adjustVector(Vector v, AxisOrder order) {
        switch (order) {
            case YXZ:
                return new Vector(v.getY(), v.getX(), v.getZ());
            case ZYX:
                return new Vector(v.getZ(), v.getY(), v.getX());
            default:
                return v.clone();
        }
    }

    @Override
    public AABB toAABB() {
        return new AABB(min, max);
    }

    public BoundingBox toBoundingBox() {
        if (!NumberConversions.isFinite(min.getX()) || !NumberConversions.isFinite(max.getX()) ||
                !NumberConversions.isFinite(min.getY()) || !NumberConversions.isFinite(max.getY()) ||
                !NumberConversions.isFinite(min.getZ()) || !NumberConversions.isFinite(max.getZ())) return null;
        return BoundingBox.of(min, max);
    }

    private enum AxisOrder {
        XYZ, YXZ, ZYX
    }
}
package me.moros.hyperion.abilities.earthbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Sweeps the simulated hook/block against real block shapes and entity bounds. */
final class MetalCableCollision {
    record Hit(Location position, Block block, Entity entity) { }
    private record Cell(int x, int y, int z) { }

    private MetalCableCollision() { }

    static Hit trace(Location start, Vector movement, double blockRadius, double entityRadius,
                     Predicate<Entity> targets) {
        final World world = start.getWorld();
        final Vector from = start.toVector();
        final Vector to = from.clone().add(movement);
        final double radius = Math.max(blockRadius, entityRadius);
        final BoundingBox area = new BoundingBox(
                new Vector(Math.min(from.x, to.x) - radius, Math.min(from.y, to.y) - radius,
                        Math.min(from.z, to.z) - radius),
                new Vector(Math.max(from.x, to.x) + radius, Math.max(from.y, to.y) + radius,
                        Math.max(from.z, to.z) + radius));
        double nearest = Double.POSITIVE_INFINITY;
        Block hitBlock = null;
        Entity hitEntity = null;
        // Traverse small swept spans instead of visiting the entire diagonal enclosing cube.
        final Set<Cell> visited = new HashSet<>();
        final int steps = Math.max(1, (int) Math.ceil(movement.length() * 2));
        Vector a = from;
        for (int step = 1; step <= steps; step++) {
            final Vector b = from.clone().add(movement.clone().multiply((double) step / steps));
            for (int x = (int) Math.floor(Math.min(a.x, b.x) - blockRadius); x <= Math.floor(Math.max(a.x, b.x) + blockRadius); x++) {
                // Fences/walls can extend above their own cell.
                for (int y = (int) Math.floor(Math.min(a.y, b.y) - blockRadius) - 1; y <= Math.floor(Math.max(a.y, b.y) + blockRadius); y++) {
                    for (int z = (int) Math.floor(Math.min(a.z, b.z) - blockRadius); z <= Math.floor(Math.max(a.z, b.z) + blockRadius); z++) {
                        if (!visited.add(new Cell(x, y, z))) continue;
                        final boolean unavailable = y < world.getMinHeight() || y >= world.getMaxHeight()
                                || !world.isChunkLoaded(x >> 4, z >> 4);
                        final Block block = unavailable ? null : world.getBlockAt(x, y, z);
                        final List<BoundingBox> boxes = unavailable || block.isLiquid()
                                ? List.of(new BoundingBox(new Vector(x, y, z), new Vector(x + 1, y + 1, z + 1)))
                                : block.getCollisionBoxes();
                        for (final BoundingBox box : boxes) {
                            final double time = intersection(from, movement, box.expand(blockRadius));
                            if (time < nearest) {
                                nearest = time;
                                hitBlock = block;
                            }
                        }
                    }
                }
            }
            a = b;
        }
        if (targets != null) {
            for (final Entity entity : world.getNearbyEntities(area, targets)) {
                final double time = intersection(from, movement, entity.getBoundingBox().expand(entityRadius));
                // A block wins ties, so an entity cannot be grabbed through a wall.
                if (time < nearest) {
                    nearest = time;
                    hitBlock = null;
                    hitEntity = entity;
                }
            }
        }
        return Double.isFinite(nearest)
                ? new Hit(start.clone().add(movement.clone().multiply(nearest)), hitBlock, hitEntity) : null;
    }

    private static double intersection(Vector from, Vector delta, BoundingBox box) {
        double enter = 0;
        double leave = 1;
        final double[] position = {from.x, from.y, from.z};
        final double[] movement = {delta.x, delta.y, delta.z};
        final double[] min = {box.getMinX(), box.getMinY(), box.getMinZ()};
        final double[] max = {box.getMaxX(), box.getMaxY(), box.getMaxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(movement[axis]) < 1.0E-10) {
                if (position[axis] < min[axis] || position[axis] > max[axis]) return Double.POSITIVE_INFINITY;
            } else {
                double near = (min[axis] - position[axis]) / movement[axis];
                double far = (max[axis] - position[axis]) / movement[axis];
                if (near > far) { double swap = near; near = far; far = swap; }
                enter = Math.max(enter, near);
                leave = Math.min(leave, far);
                if (enter > leave) return Double.POSITIVE_INFINITY;
            }
        }
        return enter;
    }
}

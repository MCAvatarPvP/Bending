package me.moros.hyperion.abilities.earthbending;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.BlockDisplay;
import com.projectkorra.projectkorra.platform.mc.entity.Display;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/** Damped Verlet rope, pinned at both ends, rendered with reusable steel segments. */
final class MetalCableRope {
    private static final float WIDTH = 0.075F;
    private final List<Vector> points = new ArrayList<>();
    private final List<Vector> previous = new ArrayList<>();
    private final List<BlockDisplay> segments = new ArrayList<>();
    private final List<BlockDisplay> hook = new ArrayList<>();

    List<Location> update(Location hand, Location end, Vector facing, boolean taut) {
        final double distance = hand.distance(end);
        final int count = Math.max(3, Math.min(48, (int) Math.ceil(distance / 0.65)));
        resize(hand, end, count);
        final Vector first = hand.toVector();
        final Vector last = end.toVector();
        for (int i = 1; i < count; i++) {
            final Vector point = points.get(i);
            final Vector velocity = point.clone().subtract(previous.get(i)).multiply(0.86);
            // Large corrections (teleports/fast turns) must not fling the rope out of view.
            if (velocity.lengthSquared() > 1) velocity.normalize();
            previous.get(i).copy(point);
            point.add(velocity).add(new Vector(0, -0.035, 0));
        }
        final double slack = taut ? Math.min(0.10, distance * 0.006) : Math.min(0.8, distance * 0.06);
        final double length = (distance + slack) / count;
        for (int iteration = 0; iteration < 12; iteration++) {
            points.getFirst().copy(first);
            points.getLast().copy(last);
            // Alternate passes so the hook end and hand end converge evenly.
            for (int step = 0; step < count; step++) {
                final int i = iteration % 2 == 0 ? step : count - step - 1;
                final Vector a = points.get(i);
                final Vector b = points.get(i + 1);
                final Vector delta = b.clone().subtract(a);
                final double current = delta.length();
                if (current < 1.0E-8) continue;
                delta.multiply((current - length) / current);
                if (i == 0) b.subtract(delta);
                else if (i + 1 == count) a.add(delta);
                else { a.add(delta.clone().multiply(0.5)); b.subtract(delta.multiply(0.5)); }
            }
            for (int i = 1; i < count; i++) keepOutsideBlocks(hand.getWorld(), points.get(i));
        }
        points.getFirst().copy(first);
        points.getLast().copy(last);
        previous.getFirst().copy(first);
        previous.getLast().copy(last);
        final List<Location> locations = new ArrayList<>(count + 1);
        for (final Vector point : points) locations.add(point.toLocation(hand.getWorld()));
        for (int i = 0; i < count; i++) {
            if (!segments.get(i).isValid()) segments.set(i, spawn(hand, Material.IRON_BLOCK.createBlockData()));
            rod(segments.get(i), locations.get(i), locations.get(i + 1), WIDTH);
        }
        renderHook(end, facing);
        return locations;
    }

    private void resize(Location hand, Location end, int count) {
        if (points.size() != count + 1) {
            final List<Vector> old = new ArrayList<>(points);
            final List<Vector> oldPrevious = new ArrayList<>(previous);
            points.clear();
            previous.clear();
            for (int i = 0; i <= count; i++) {
                final double fraction = (double) i / count;
                if (old.isEmpty()) {
                    final Vector point = hand.toVector().multiply(1 - fraction).add(end.toVector().multiply(fraction));
                    points.add(point);
                    previous.add(point.clone());
                } else {
                    points.add(sample(old, fraction));
                    previous.add(sample(oldPrevious, fraction));
                }
            }
        }
        while (segments.size() < count) segments.add(spawn(hand, Material.IRON_BLOCK.createBlockData()));
        while (segments.size() > count) segments.removeLast().remove();
    }

    private static Vector sample(List<Vector> values, double fraction) {
        final double index = fraction * (values.size() - 1);
        final int low = (int) index;
        return values.get(low).clone().multiply(1 - (index - low))
                .add(values.get(Math.min(low + 1, values.size() - 1)).clone().multiply(index - low));
    }

    private static void keepOutsideBlocks(World world, Vector point) {
        if (!world.isChunkLoaded((int) Math.floor(point.x) >> 4, (int) Math.floor(point.z) >> 4)) return;
        for (int y = (int) Math.floor(point.y) - 1; y <= Math.floor(point.y); y++) {
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) continue;
            final Block block = world.getBlockAt((int) Math.floor(point.x), y, (int) Math.floor(point.z));
            for (final BoundingBox shape : block.getCollisionBoxes()) {
                final BoundingBox box = shape.expand(WIDTH / 2.0);
                if (point.x <= box.getMinX() || point.x >= box.getMaxX()
                        || point.y <= box.getMinY() || point.y >= box.getMaxY()
                        || point.z <= box.getMinZ() || point.z >= box.getMaxZ()) continue;
                final double[] distances = {point.x - box.getMinX(), box.getMaxX() - point.x,
                        point.y - box.getMinY(), box.getMaxY() - point.y,
                        point.z - box.getMinZ(), box.getMaxZ() - point.z};
                int face = 0;
                for (int i = 1; i < distances.length; i++) if (distances[i] < distances[face]) face = i;
                switch (face) {
                    case 0 -> point.x = box.getMinX();
                    case 1 -> point.x = box.getMaxX();
                    case 2 -> point.y = box.getMinY();
                    case 3 -> point.y = box.getMaxY();
                    case 4 -> point.z = box.getMinZ();
                    default -> point.z = box.getMaxZ();
                }
            }
        }
    }

    private void renderHook(Location tip, Vector direction) {
        while (hook.size() < 3) hook.add(spawn(tip, Material.IRON_BLOCK.createBlockData()));
        for (int i = 0; i < hook.size(); i++) {
            if (!hook.get(i).isValid()) hook.set(i, spawn(tip, Material.IRON_BLOCK.createBlockData()));
        }
        final Vector forward = direction.clone().normalize();
        if (forward.lengthSquared() < 0.001) forward.setZ(1);
        Vector side = forward.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 0.001) side = new Vector(1, 0, 0);
        side.normalize().multiply(0.16);
        final Location back = tip.clone().subtract(forward.clone().multiply(0.36));
        final Location barb = tip.clone().subtract(forward.clone().multiply(0.24));
        rod(hook.get(0), back, tip, 0.12F);
        rod(hook.get(1), barb.clone().add(side), tip, 0.07F);
        rod(hook.get(2), barb.clone().subtract(side), tip, 0.07F);
    }

    static BlockDisplay spawn(Location location, BlockData data) {
        final Location position = location.clone();
        position.setYaw(0);
        position.setPitch(0);
        final BlockDisplay display = position.getWorld().spawn(position, BlockDisplay.class);
        display.setBlock(data);
        display.setPersistent(false);
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setSilent(true);
        display.setBillboard(Display.Billboard.FIXED);
        display.setShadowRadius(0);
        display.setShadowStrength(0);
        display.setViewRange(1.5F);
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
        return display;
    }

    private static void rod(BlockDisplay display, Location from, Location to, float width) {
        final Vector delta = to.toVector().subtract(from.toVector());
        final float length = (float) Math.max(0.001, delta.length());
        final Quaternionf rotation = delta.lengthSquared() < 1.0E-8 ? new Quaternionf()
                : new Quaternionf().rotationTo(new Vector3f(0, 1, 0),
                new Vector3f((float) delta.x, (float) delta.y, (float) delta.z).normalize());
        final Vector3f offset = rotation.transform(new Vector3f(-width / 2, 0, -width / 2));
        display.setTransformation(new Transformation(offset, rotation, new Vector3f(width, length, width), new Quaternionf()));
        final Location position = from.clone();
        position.setYaw(0);
        position.setPitch(0);
        display.teleport(position);
    }

    void remove() {
        segments.forEach(BlockDisplay::remove);
        hook.forEach(BlockDisplay::remove);
        segments.clear();
        hook.clear();
        points.clear();
        previous.clear();
    }
}

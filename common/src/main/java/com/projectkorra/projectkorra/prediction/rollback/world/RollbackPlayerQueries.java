package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Ability targeting reads the current private body and terrain, including changes made during replay. */
final class RollbackPlayerQueries {
    private RollbackPlayerQueries() { }

    @SuppressWarnings("WrapperReferenceEquality") // Reject views retained from a discarded branch, including a reused UUID.
    static boolean lineOfSight(RollbackPlayerState player, Entity target) {
        var world = world(player); var targetBody = RollbackEntityBody.logicalBody(target);
        if (world.entities().get(target.getUniqueId()) != target) throw new IllegalArgumentException("Sight target is not in the private arena");
        var from = player.living().eyeLocation();
        double eyeHeight;
        if (target instanceof LivingEntity living) eyeHeight = living.getEyeHeight();
        else if (targetBody.kinematicsSource() != null) eyeHeight = targetBody.kinematicsSource().eyeHeight();
        else throw new IllegalStateException("Sight target has no native eye height");
        if (!Double.isFinite(eyeHeight) || eyeHeight < 0) throw new IllegalStateException("Invalid native eye height");
        var to = target.getLocation().add(0, eyeHeight, 0);
        var delta = to.toVector().subtract(from.toVector());
        double squared = delta.lengthSquared();
        // Vanilla LivingEntity.hasLineOfSight is bounded to 128 blocks and uses the viewer's collision context.
        if (squared > 128D * 128D) return false;
        if (squared == 0) return true;
        return world.traceBlocks(from, to, FluidCollisionMode.NEVER, true, player.view()) == null;
    }

    static Block exactTarget(RollbackPlayerState player, int range) {
        var world = world(player); var origin = player.living().eyeLocation();
        var hit = world.traceBlocks(origin, origin.getDirection(), range, FluidCollisionMode.NEVER, true);
        if (hit == null) return null;
        var position = hit.block();
        // Retain the native hit's block coordinate: flooring a hit on a negative face selects the wrong block.
        return world.getBlockAt(position.x(), position.y(), position.z());
    }

    static Block targetBlock(RollbackPlayerState player, Set<Material> transparent, int range) {
        return targetBlocks(player, transparent, range, false).getLast();
    }

    static List<Block> targetBlocks(RollbackPlayerState player, Set<Material> transparent, int range) {
        return targetBlocks(player, transparent, range, true);
    }

    private static List<Block> targetBlocks(RollbackPlayerState player, Set<Material> transparent, int range, boolean lastTwo) {
        // Match BukkitMC's existing targeting sampler, including its distinct origin handling
        // for getTargetBlock versus getLastTwoTargetBlocks. This is not a collision-shape ray.
        var world = world(player); var origin = player.living().eyeLocation();
        var direction = origin.getDirection().normalize();
        var passThrough = transparent == null ? new HashSet<Material>() : new HashSet<>(transparent);
        passThrough.add(Material.AIR); passThrough.add(Material.CAVE_AIR); passThrough.add(Material.VOID_AIR);
        Block previous = world.getBlockAt(origin), current = previous, last = previous;
        int samples = 0;
        for (double distance = 0; distance <= range; distance += 0.2) {
            if (++samples > RollbackBlockRay.MAX_VISITED_BLOCKS) throw new IllegalStateException("Target block query budget exceeded");
            Block next = world.getBlockAt(origin.clone().add(direction.clone().multiply(distance)));
            if (lastTwo && next.equals(last)) continue;
            last = next; previous = current; current = next;
            if (!passThrough.contains(current.getType())) break;
        }
        return lastTwo ? List.of(previous, current) : List.of(current);
    }

    static List<Entity> nearby(RollbackPlayerState player, double x, double y, double z) {
        var world = world(player); var body = player.living().body(); var box = body.view().getBoundingBox();
        var expanded = new BoundingBox(new Vector(box.getMinX() - x, box.getMinY() - y, box.getMinZ() - z),
                new Vector(box.getMaxX() + x, box.getMaxY() + y, box.getMaxZ() + z));
        return List.copyOf(world.getNearbyEntities(expanded, entity -> !entity.getUniqueId().equals(body.identity().uuid())));
    }

    // A discarded branch can contain a different view with the same UUID; require the registered instance.
    @SuppressWarnings("WrapperReferenceEquality")
    private static RollbackWorld world(RollbackPlayerState player) {
        var body = player.living().body();
        if (!(body.world() instanceof RollbackWorld world) || world.entities().get(body.identity().uuid()) != body.view()) {
            throw new IllegalStateException("Player query requires membership in its private arena");
        }
        return world;
    }
}

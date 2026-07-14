package com.jedk1.jedcore.collision;

import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.platform.mc.GameMode;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.colliders.AABB;
import com.projectkorra.projectkorra.util.colliders.Collider;
import com.projectkorra.projectkorra.util.colliders.Ray;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class CollisionDetector {
    public static boolean checkEntityCollisions(Player player, Collider collider, CollisionCallback function) {
        return checkEntityCollisions(player, collider, function, true);
    }

    // Checks a collider to see if it's hitting any entities near it.
    // Calls the CollisionCallback when hitting a target.
    // Returns true if it hits a target.
    public static boolean checkEntityCollisions(Player player, Collider collider, CollisionCallback callback, boolean livingOnly) {
        boolean hit = false;

        Predicate<Entity> entityPredicate = entity ->
                !entity.equals(player) &&
                        !(entity instanceof ArmorStand) &&
                        !(entity instanceof Player &&
                                ((Player) entity).getGameMode() == GameMode.SPECTATOR) &&
                        (!livingOnly || entity instanceof LivingEntity);
        for (Entity entity : collider.getEntities(entityPredicate)) {
            if (callback.onCollision(entity)) {
                return true;
            }

            hit = true;
        }

        return hit;
    }

    // Checks if the entity is on the ground. Uses NMS bounding boxes for accuracy.
    public static boolean isOnGround(Entity entity) {
        AABB entityBounds = new AABB(entity.getWorld(), entity.getBoundingBox().shift(0, -0.01, 0));
        return !entityBounds.getBlocks(b -> !ElementalAbility.isAir(b.getType())).isEmpty();
    }

    public static double distanceAboveGround(Entity entity) {
        return distanceAboveGround(entity, Collections.emptySet());
    }

    // Cast a ray down to find how far above the ground this entity is.
    public static double distanceAboveGround(Entity entity, Set<Material> groundMaterials) {
        Location location = entity.getLocation().clone();
        Ray ray = new Ray(location, new Vector(0, -1, 0), location.getY());

        for (Block block : ray.getBlocks(b -> !b.isPassable())) {
            AABB blockBounds = new AABB(block.getWorld(), block.getBoundingBox());

            if (groundMaterials.contains(block.getType())) {
                Location blockLoc = block.getLocation();
                blockBounds = new AABB(blockLoc, blockLoc.clone().add(1, 1, 1));
            }

            Optional<Double> hitDistance = ray.hitDistance(blockBounds);
            if (hitDistance.isPresent())
                return hitDistance.get();
        }

        return Double.MAX_VALUE;
    }

    public interface CollisionCallback {
        // return true to break out of the loop
        boolean onCollision(Entity e);
    }
}

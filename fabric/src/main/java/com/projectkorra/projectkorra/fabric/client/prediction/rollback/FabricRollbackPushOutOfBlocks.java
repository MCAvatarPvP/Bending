package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPushOutOfBlocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;

/** Native client collision predicate and velocity setter over the owned player. */
final class FabricRollbackPushOutOfBlocks {
    private FabricRollbackPushOutOfBlocks() { }
    static void apply(PlayerEntity player) { RollbackPushOutOfBlocks.apply(new Body(player)); }
    private record Body(PlayerEntity player) implements RollbackPushOutOfBlocks.Body {
        @Override public double x() { return player.getX(); }
        @Override public double z() { return player.getZ(); }
        @Override public float width() { return player.getWidth(); }
        @Override public boolean noClip() { return player.noClip; }
        @Override public boolean collides(int x, int z) {
            var box = player.getBoundingBox();
            return player.getEntityWorld().canCollide(player, new Box(x, box.minY, z, x + 1.0, box.maxY, z + 1.0).contract(1.0E-7));
        }
        @Override public void push(RollbackPushOutOfBlocks.Direction direction) {
            var velocity = player.getVelocity();
            if (direction.x() != 0) player.setVelocity(.1 * direction.x(), velocity.y, velocity.z);
            else player.setVelocity(velocity.x, velocity.y, .1 * direction.z());
        }
    }
}

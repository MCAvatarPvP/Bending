package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;

/** Same client escape phase before private Paper movement; native geometry reads only captured terrain. */
final class PaperRollbackPushOutOfBlocks {
    private PaperRollbackPushOutOfBlocks() { }
    static void apply(Player player) { RollbackPushOutOfBlocks.apply(new Body(player)); }
    private record Body(Player player) implements RollbackPushOutOfBlocks.Body {
        @Override public double x() { return player.getX(); }
        @Override public double z() { return player.getZ(); }
        @Override public float width() { return player.getBbWidth(); }
        @Override public boolean noClip() { return player.noPhysics; }
        @Override public boolean collides(int x, int z) {
            var box = player.getBoundingBox();
            return player.level().collidesWithSuffocatingBlock(player, new AABB(x, box.minY, z, x + 1.0, box.maxY, z + 1.0).deflate(1.0E-7));
        }
        @Override public void push(RollbackPushOutOfBlocks.Direction direction) {
            var velocity = player.getDeltaMovement();
            if (direction.x() != 0) player.setDeltaMovement(.1 * direction.x(), velocity.y, velocity.z);
            else player.setDeltaMovement(velocity.x, velocity.y, .1 * direction.z());
        }
    }
}

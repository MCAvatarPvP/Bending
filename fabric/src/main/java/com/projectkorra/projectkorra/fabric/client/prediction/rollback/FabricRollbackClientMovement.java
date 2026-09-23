package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackVerticalInput;
import net.minecraft.entity.player.PlayerEntity;

/** Client-only input forces over the private native player; travel, gravity and damping remain native. */
final class FabricRollbackClientMovement {
    private FabricRollbackClientMovement() { }
    static void apply(PlayerEntity player) {
        FabricRollbackPushOutOfBlocks.apply(player);
        vertical(player);
    }
    static void vertical(PlayerEntity player) { RollbackVerticalInput.apply(new Body(player)); }
    private record Body(PlayerEntity player) implements RollbackVerticalInput.Body {
        @Override public boolean touchingWater() { return player.isTouchingWater(); }
        @Override public boolean affectedByFluids() { return player.shouldSwimInFluids(); }
        @Override public boolean sneaking() { return player.isSneaking(); }
        @Override public boolean jumping() { return player.isJumping(); }
        @Override public boolean flying() { return player.getAbilities().flying; }
        @Override public float flySpeed() { return player.getAbilities().getFlySpeed(); }
        @Override public void addVertical(double amount) { player.setVelocity(player.getVelocity().add(0, amount, 0)); }
    }
}

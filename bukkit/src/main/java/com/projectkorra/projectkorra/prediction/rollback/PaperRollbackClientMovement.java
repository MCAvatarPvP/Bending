package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.world.entity.player.Player;

/** Client-only input forces executed in the owned Paper tick, before the original player movement body. */
final class PaperRollbackClientMovement {
    private PaperRollbackClientMovement() { }
    static void apply(Player player) {
        PaperRollbackPushOutOfBlocks.apply(player);
        vertical(player);
    }
    static void vertical(Player player) { RollbackVerticalInput.apply(new Body(player)); }
    private record Body(Player player) implements RollbackVerticalInput.Body {
        @Override public boolean touchingWater() { return player.isInWater(); }
        @Override public boolean affectedByFluids() { return player.isAffectedByFluids(); }
        @Override public boolean sneaking() { return player.isShiftKeyDown(); }
        @Override public boolean jumping() { return player.isJumping(); }
        @Override public boolean flying() { return player.getAbilities().flying; }
        @Override public float flySpeed() { return player.getAbilities().getFlyingSpeed(); }
        @Override public void addVertical(double amount) { player.setDeltaMovement(player.getDeltaMovement().add(0, amount, 0)); }
    }
}

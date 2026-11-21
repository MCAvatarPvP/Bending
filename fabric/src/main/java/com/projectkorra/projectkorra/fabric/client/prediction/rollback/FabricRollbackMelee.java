package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import net.minecraft.entity.player.PlayerEntity;

/** Captured Paper melee settings apply only to a private, enrolled native player. */
public final class FabricRollbackMelee {
    private FabricRollbackMelee() { }
    private static FabricRollbackWorldAccess.DamagePolicy<?> policy(PlayerEntity player) {
        if (!(player instanceof FabricRollbackDamagePlayer)) return null;
        var owner = FabricRollbackGliding.owner(player);
        return owner.hasRound() ? owner.damagePolicy() : null;
    }
    public static boolean disableCrits(PlayerEntity player) {
        var policy = policy(player); return policy != null && policy.disablePlayerCrits();
    }
    public static boolean clearSprint(PlayerEntity player) {
        var policy = policy(player); return policy == null || !policy.disableSprintInterruptionOnAttack();
    }
    public static float exhaustion(PlayerEntity player, float ordinary) {
        var policy = policy(player); return policy == null ? ordinary : policy.combatExhaustion();
    }
}

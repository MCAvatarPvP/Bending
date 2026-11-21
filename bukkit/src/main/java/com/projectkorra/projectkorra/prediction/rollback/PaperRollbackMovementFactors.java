package com.projectkorra.projectkorra.prediction.rollback;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.UseEffects;

/** Native policy reads at the owned server player's input phase; no live player or client class is used. */
final class PaperRollbackMovementFactors {
    private PaperRollbackMovementFactors() { }
    static void apply(Player player) {
        var axes = RollbackMovementFactors.apply(player.xxa, player.zza,
                player.isUsingItem() && !player.isPassenger() ? player.getUseItem().getOrDefault(DataComponents.USE_EFFECTS, UseEffects.DEFAULT).speedMultiplier() : 1F,
                player.isCrouching() || player.isVisuallyCrawling() ? (float) player.getAttributeValue(Attributes.SNEAKING_SPEED) : 1F);
        player.xxa = axes.strafe(); player.zza = axes.forward();
    }
}

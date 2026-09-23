package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.prediction.rollback.RollbackMovementFactors;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.UseEffectsComponent;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** Owned replica body: use the client's input factors at the native input phase, followed by ordinary native travel. */
public abstract class FabricRollbackSimulatedPlayer extends PlayerEntity {
    protected FabricRollbackSimulatedPlayer(World world, GameProfile profile) { super(world, profile); }
    @Override public void startGliding() {
        if (FabricRollbackGliding.allowed(this, true)) super.startGliding();
        else super.stopGliding(); // Paper marks both transitions when a start request is cancelled.
    }
    @Override public void stopGliding() {
        if (FabricRollbackGliding.allowed(this, false)) super.stopGliding();
    }
    @Override protected void tickGliding() {
        if (!canGlide()) {
            limitFallDistance();
            if (isGliding() && FabricRollbackGliding.allowed(this, false)) setFlag(7, false);
            return;
        }
        super.tickGliding(); // Preserve native wear cadence, RNG and causal game events.
    }
    @Override public void tickMovement() {
        FabricRollbackClientMovement.apply(this);
        super.tickMovement();
    }
    @Override public void tick() {
        FabricRollbackAutoJump.begin(this);
        super.tick();
        FabricRollbackAutoJump.end(this);
    }
    @Override public void move(MovementType type, Vec3d movement) {
        double x = getX(), z = getZ();
        super.move(type, movement);
        FabricRollbackAutoJump.moved(this, (float) (getX() - x), (float) (getZ() - z));
    }
    @Override protected void tickMovementInput() {
        var axes = RollbackMovementFactors.apply(sidewaysSpeed, forwardSpeed,
                isUsingItem() && !hasVehicle() ? getActiveItem().getOrDefault(DataComponentTypes.USE_EFFECTS, UseEffectsComponent.DEFAULT).speedMultiplier() : 1F,
                isInSneakingPose() || isCrawling() ? (float) getAttributeValue(EntityAttributes.SNEAKING_SPEED) : 1F);
        sidewaysSpeed = axes.strafe(); forwardSpeed = axes.forward();
    }
}

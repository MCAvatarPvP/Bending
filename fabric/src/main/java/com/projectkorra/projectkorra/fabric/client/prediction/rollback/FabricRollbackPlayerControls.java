package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.EntityRollbackControlAccess;
import com.projectkorra.projectkorra.fabric.mixin.client.HungerManagerRollbackAccess;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Controls;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Flag;

import java.util.EnumSet;
import java.util.Objects;

import static com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Flag.*;

/** Ability control changes over the private native player, with Paper's API speed units. */
final class FabricRollbackPlayerControls {
    private final FabricRollbackNativePlayerState owner;
    FabricRollbackPlayerControls(FabricRollbackNativePlayerState owner) { this.owner = owner; }
    Controls read() {
        var player = owner.ownedPlayer(); var abilities = player.getAbilities();
        var flags = EnumSet.noneOf(Flag.class);
        if (abilities.flying) flags.add(FLYING);
        if (abilities.allowFlying) flags.add(ALLOW_FLIGHT);
        if (player.isSneaking()) flags.add(SNEAKING);
        if (player.isSprinting()) flags.add(SPRINTING);
        if (player.isGliding()) flags.add(GLIDING);
        if (player.isSwimming()) flags.add(SWIMMING);
        if (player.isGlowing()) flags.add(GLOWING);
        if (owner.pickupItems()) flags.add(PICKUP_ITEMS);
        return new Controls(flags, abilities.getFlySpeed() * 2F, player.experienceProgress,
                ((HungerManagerRollbackAccess) player.getHungerManager()).rollback$exhaustion());
    }
    void write(RollbackPlayerState target, RollbackPlayerState.Control changed, Controls next) {
        Objects.requireNonNull(next, "controls");
        if (target.living().body().kinematicsSource() != owner || target.living().combatSource() != owner || target.inventory().nativeOwner() != owner) {
            throw new IllegalArgumentException("Control target belongs to another native player");
        }
        var player = owner.ownedPlayer(); var abilities = player.getAbilities();
        switch (Objects.requireNonNull(changed, "changed control")) {
            case ALLOW_FLIGHT -> {
                abilities.allowFlying = next.has(ALLOW_FLIGHT);
                if (!abilities.allowFlying) abilities.flying = false;
                player.sendAbilitiesUpdate();
            }
            case FLYING -> {
                boolean flying = next.has(FLYING);
                if (flying && !abilities.allowFlying) throw new IllegalArgumentException("Player is not allowed to fly");
                boolean update = abilities.flying != flying;
                abilities.flying = flying;
                if (update) player.sendAbilitiesUpdate();
            }
            case FLY_SPEED -> { abilities.setFlySpeed(next.flySpeed() / 2F); player.sendAbilitiesUpdate(); }
            case SNEAKING -> player.setSneaking(next.has(SNEAKING));
            case SPRINTING -> player.setSprinting(next.has(SPRINTING));
            // Same tracked gliding bit as CraftLivingEntity.setGliding.
            case GLIDING -> ((EntityRollbackControlAccess) player).rollback$setFlag(7, next.has(GLIDING));
            case SWIMMING -> player.setSwimming(next.has(SWIMMING));
            case GLOWING -> player.setGlowing(next.has(GLOWING));
            case PICKUP_ITEMS -> owner.pickupItems(next.has(PICKUP_ITEMS));
            case EXPERIENCE -> player.experienceProgress = next.experience();
            case EXHAUSTION -> ((HungerManagerRollbackAccess) player.getHungerManager()).rollback$exhaustion(next.exhaustion());
        }
    }
}

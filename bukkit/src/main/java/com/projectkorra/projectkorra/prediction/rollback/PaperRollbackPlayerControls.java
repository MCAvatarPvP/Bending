package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Controls;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Flag;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumSet;
import java.util.Objects;

import static com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayerState.Flag.*;

/** Typed control mutations on the same private player that supplies movement and combat. */
final class PaperRollbackPlayerControls {
    private final PaperRollbackNativePlayerState owner;
    private final PaperRollbackWorldAccess world;
    PaperRollbackPlayerControls(PaperRollbackNativePlayerState owner, PaperRollbackWorldAccess world) {
        this.owner = owner; this.world = world;
    }
    Controls read() {
        var player = owner.ownedPlayer(); var abilities = player.getAbilities();
        var flags = EnumSet.noneOf(Flag.class);
        if (abilities.flying) flags.add(FLYING);
        if (abilities.mayfly) flags.add(ALLOW_FLIGHT);
        if (player.isShiftKeyDown()) flags.add(SNEAKING);
        if (player.isSprinting()) flags.add(SPRINTING);
        if (player.isFallFlying()) flags.add(GLIDING);
        if (player.isSwimming()) flags.add(SWIMMING);
        if (player.isCurrentlyGlowing()) flags.add(GLOWING);
        if (player.bukkitPickUpLoot) flags.add(PICKUP_ITEMS);
        return new Controls(flags, abilities.flyingSpeed * 2F, player.experienceProgress, player.getFoodData().exhaustionLevel);
    }
    void write(RollbackPlayerState target, RollbackPlayerState.Control changed, Controls next) {
        Objects.requireNonNull(next, "controls");
        if (target.living().body().kinematicsSource() != owner || target.living().combatSource() != owner || target.inventory().nativeOwner() != owner) {
            throw new IllegalArgumentException("Control target belongs to another native player");
        }
        var player = owner.ownedPlayer(); var abilities = player.getAbilities();
        // Preserve which setter was called even if its visible value is unchanged.
        // In particular, glowing from a potion is distinct from the API's glowing tag.
        switch (Objects.requireNonNull(changed, "changed control")) {
            case ALLOW_FLIGHT -> {
                abilities.mayfly = next.has(ALLOW_FLIGHT);
                if (!abilities.mayfly) abilities.flying = false;
                player.onUpdateAbilities();
            }
            case FLYING -> {
                boolean flying = next.has(FLYING);
                if (flying && !abilities.mayfly) throw new IllegalArgumentException("Player is not allowed to fly");
                boolean update = abilities.flying != flying;
                abilities.flying = flying;
                if (update) player.onUpdateAbilities();
            }
            case FLY_SPEED -> { abilities.flyingSpeed = next.flySpeed() / 2F; player.onUpdateAbilities(); }
            case SNEAKING -> player.setShiftKeyDown(next.has(SNEAKING));
            case SPRINTING -> player.setSprinting(next.has(SPRINTING));
            // CraftLivingEntity.setGliding writes flag 7 without start/stop-flight side effects.
            case GLIDING -> player.setSharedFlag(7, next.has(GLIDING));
            case SWIMMING -> world.swimming(player, next.has(SWIMMING));
            case GLOWING -> player.setGlowingTag(next.has(GLOWING));
            case PICKUP_ITEMS -> player.bukkitPickUpLoot = next.has(PICKUP_ITEMS);
            case EXPERIENCE -> {
                player.experienceProgress = next.experience();
                if (player instanceof ServerPlayer serverPlayer) serverPlayer.lastSentExp = -1;
            }
            case EXHAUSTION -> player.getFoodData().exhaustionLevel = next.exhaustion();
        }
    }
}

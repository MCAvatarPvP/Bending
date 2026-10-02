package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.item.ItemStack;
import java.util.Objects;

/** Physical keyboard/toggle state stays outside replay; native eligibility reads the latest private player state. */
public final class FabricRollbackClientControls {
    private final Thread thread = Thread.currentThread();
    private final FabricRollbackNativePlayerState source;
    private final ClientPlayerEntity probe;
    private final FabricRollbackAutoJump autoJump;
    private final PlayerAbilities queryAbilities = new PlayerAbilities();
    private PlayerEntity reading;
    private boolean sprinting, observedSprint;
    private int doubleTapTicks;
    private int flightTapTicks, observedAge;
    private boolean flying, observedFlight;
    private Boolean flightRequest;
    private boolean gliding, observedGlide, glideRequest;
    private long tick = -1;

    public FabricRollbackClientControls(FabricRollbackNativePlayerState source) {
        this.source = Objects.requireNonNull(source);
        autoJump = new FabricRollbackAutoJump(source);
        sprinting = observedSprint = source.use(PlayerEntity::isSprinting);
        source.use(player -> { flying = observedFlight = player.getAbilities().flying; gliding = observedGlide = player.isGliding(); observedAge = player.age; return null; });
        probe = RollbackNativeQueryShell.create(ClientPlayerEntity.class)
                .query(PlayerEntity::getAbilities, null, args -> queryAbilities)
                .query(PlayerEntity::getHungerManager, null, args -> reading.getHungerManager())
                .query(PlayerEntity::getVehicle, null, args -> reading.getVehicle())
                .query(PlayerEntity::hasVehicle, false, args -> reading.hasVehicle())
                .query(PlayerEntity::isUsingItem, false, args -> reading.isUsingItem())
                .query(PlayerEntity::hasBlindnessEffect, false, args -> reading.hasBlindnessEffect())
                .query(PlayerEntity::isPartlyTouchingWater, false, args -> reading.isPartlyTouchingWater())
                .query(PlayerEntity::isTouchingWater, false, args -> reading.isTouchingWater())
                .query(PlayerEntity::isSubmergedInWater, false, args -> reading.isSubmergedInWater())
                .query(PlayerEntity::isGliding, false, args -> reading.isGliding())
                .query(PlayerEntity::isInSneakingPose, false, args -> reading.isInSneakingPose())
                .query(PlayerEntity::isCrawling, false, args -> reading.isCrawling())
                .query(PlayerEntity::isOnGround, false, args -> reading.isOnGround())
                .query(PlayerEntity::isSprinting, false, args -> sprinting)
                .nativeQuery(ClientPlayerEntity::canSprintOrFly, false, args -> requireReading())
                .nativeQuery(ClientPlayerEntity::shouldSlowDown, false, args -> requireReading()).instance();
        // Access-widened private predicates remain final native bodies. Their virtual reads resolve only the bindings above.
    }

    /** Invoked once at the replaced native player tick, before the end-of-client-tick replica step. */
    public void tick(long next, Input input, int sprintWindow, boolean autoJumpEnabled) {
        check(); Objects.requireNonNull(input);
        if (next < 0 || next == Long.MAX_VALUE || tick >= 0 && next != tick + 1) throw new IllegalStateException("Native input clock skipped or repeated");
        if (sprintWindow < 0) throw new IllegalArgumentException("Sprint window");
        autoJump.enabled(autoJumpEnabled);
        boolean oldForward = input.hasForwardMovement(), oldSneak = input.playerInput.sneak(), oldJump = input.playerInput.jump();
        input.tick();
        boolean automaticJump = autoJump.take();
        if (automaticJump) input.jump();
        source.use(player -> {
            if (player.hasVehicle()) {
                throw new UnsupportedOperationException("Rollback mount controls are not bound");
            }
            reading = player; probe.input = input; probe.activeItemStack = player.getActiveItem();
            probe.horizontalCollision = player.horizontalCollision; probe.collidedSoftly = player.collidedSoftly;
            try {
                // A completed simulation step can reject a request without changing the flag.
                // While authority stalls the body, retain intent so a second toggle means stop.
                var abilities = player.getAbilities();
                if (player.age != observedAge || abilities.flying != observedFlight || !abilities.allowFlying) flying = abilities.flying;
                if (player.age != observedAge || player.isGliding() != observedGlide) gliding = player.isGliding();
                observedGlide = player.isGliding(); glideRequest = false;
                observedAge = player.age; observedFlight = abilities.flying; flightRequest = null;
                queryAbilities.flying = flying; queryAbilities.allowFlying = abilities.allowFlying;
                queryAbilities.invulnerable = abilities.invulnerable; queryAbilities.creativeMode = abilities.creativeMode;
                queryAbilities.allowModifyWorld = abilities.allowModifyWorld;
                queryAbilities.setFlySpeed(abilities.getFlySpeed()); queryAbilities.setWalkSpeed(abilities.getWalkSpeed());
                // An ability or correction can change sprinting; otherwise retain physical intent while the replica is waiting for authority.
                if (player.isSprinting() != observedSprint) sprinting = player.isSprinting();
                observedSprint = player.isSprinting();
                if (doubleTapTicks > 0) doubleTapTicks--;
                if (oldSneak || probe.isBlockedFromSprinting() && !player.hasVehicle() || input.playerInput.backward()) doubleTapTicks = 0;
                if (probe.canStartSprinting()) {
                    if (!oldForward) {
                        if (doubleTapTicks > 0) sprinting = true;
                        else doubleTapTicks = sprintWindow;
                    }
                    if (input.playerInput.sprint()) sprinting = true;
                }
                if (sprinting && (player.isSwimming() ? probe.shouldStopSwimSprinting() : probe.shouldStopSprinting())) sprinting = false;
                if (abilities.allowFlying) {
                    if (player.isSpectator()) {
                        if (!flying) flightRequest = flying = true;
                    } else if (!oldJump && input.playerInput.jump() && !automaticJump) {
                        if (flightTapTicks == 0) flightTapTicks = 7;
                        else if (!player.isSwimming()) { flightRequest = flying = !flying; flightTapTicks = 0; }
                    }
                }
                if (flightRequest == null && !oldJump && input.playerInput.jump() && !flying && !gliding
                        && !player.isClimbing() && player.canGlide() && !player.isTouchingWater()) {
                    glideRequest = gliding = true;
                }
                // The native PlayerEntity base phase decrements this after testing the edge.
                if (flightTapTicks > 0) flightTapTicks--;
                tick = next;
                return null;
            } finally { reading = null; probe.input = null; probe.activeItemStack = ItemStack.EMPTY; }
        });
    }
    public boolean sprinting(long expectedTick) {
        check(); if (tick != expectedTick) throw new IllegalStateException("Replica has no matching native input frame"); return sprinting;
    }
    public Boolean flightRequest(long expectedTick) {
        check(); if (tick != expectedTick) throw new IllegalStateException("Replica has no matching native input frame"); return flightRequest;
    }
    public boolean glideRequest(long expectedTick) {
        check(); if (tick != expectedTick) throw new IllegalStateException("Replica has no matching native input frame"); return glideRequest;
    }
    void simulate(Runnable action) { check(); autoJump.simulate(action); }
    void clear() { check(); autoJump.clear(); }
    private void requireReading() { check(); if (reading == null) throw new IllegalStateException("Native input query outside its private state scope"); }
    private void check() { if (thread != Thread.currentThread()) throw new IllegalStateException("Native controls crossed threads"); }
}

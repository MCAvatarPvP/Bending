package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import java.util.Objects;

/** Observes native replica movement; a resulting jump becomes future physical input, never a replay-time input mutation. */
public final class FabricRollbackAutoJump {
    private static final ThreadLocal<FabricRollbackAutoJump> ACTIVE = new ThreadLocal<>();
    private final Thread thread = Thread.currentThread();
    private final FabricRollbackSimulatedPlayer player;
    private final Movement input = new Movement();
    private final ClientPlayerEntity probe;
    private boolean enabled, frame, reading, discarded;
    private Decision decision, observed;
    private Integer consumedAge;
    private record Decision(int age, boolean jump) { }

    FabricRollbackAutoJump(FabricRollbackNativePlayerState state) {
        if (!(state.ownedPlayer() instanceof FabricRollbackSimulatedPlayer body)) throw new IllegalArgumentException("Auto-jump requires the owned movement adapter");
        player = body;
        probe = RollbackNativeQueryShell.create(ClientPlayerEntity.class)
                .query(ClientPlayerEntity::isAutoJumpEnabled, false, args -> enabled)
                .query(PlayerEntity::isOnGround, false, args -> player.isOnGround())
                .query(PlayerEntity::clipAtLedge, false, args -> player.clipAtLedge())
                .query(PlayerEntity::hasVehicle, false, args -> player.hasVehicle())
                .query(PlayerEntity::getJumpVelocityMultiplier, 0F, args -> player.getJumpVelocityMultiplier())
                .query(PlayerEntity::getEntityPos, Vec3d.ZERO, args -> player.getEntityPos())
                .query(PlayerEntity::getMovementSpeed, 0F, args -> player.getMovementSpeed())
                .query(PlayerEntity::getYaw, 0F, args -> player.getYaw())
                .query(PlayerEntity::getRotationVecClient, Vec3d.ZERO, args -> player.getRotationVecClient())
                .query(PlayerEntity::getEntityWorld, null, args -> player.getEntityWorld())
                .query(value -> value.hasStatusEffect(StatusEffects.JUMP_BOOST), false, args -> player.hasStatusEffect(effect(args[0])))
                .query(value -> value.getStatusEffect(StatusEffects.JUMP_BOOST), null, args -> player.getStatusEffect(effect(args[0])))
                .nativeAction(value -> value.autoJump(0, 0), args -> {
                    if (ACTIVE.get() != this || !reading) throw new IllegalStateException("Auto-jump outside native movement observation");
                }).instance();
    }

    void enabled(boolean value) { check(); enabled = value; }

    /** Each published simulation head can contribute at most one automatic key press, including during authority waits/corrections. */
    boolean take() {
        check();
        if (decision == null || decision.age != player.age || Objects.equals(consumedAge, decision.age)) return false;
        consumedAge = decision.age;
        return enabled && decision.jump;
    }

    void simulate(Runnable action) {
        check(); Objects.requireNonNull(action);
        if (ACTIVE.get() != null) throw new IllegalStateException("Nested auto-jump observation");
        observed = null; frame = false; discarded = false; ACTIVE.set(this);
        try {
            action.run();
            if (!discarded && observed != null) decision = observed;
        } finally {
            ACTIVE.remove(); observed = null; frame = false; reading = false;
            probe.input = null; probe.pos = Vec3d.ZERO;
            probe.dimensions = null;
        }
    }
    void clear() { check(); decision = null; consumedAge = null; discarded = true; }

    static void begin(FabricRollbackSimulatedPlayer body) {
        var owner = ACTIVE.get(); if (owner == null || owner.player != body) return;
        if (owner.frame) throw new IllegalStateException("Nested native player tick");
        owner.frame = true; owner.input.set(body.sidewaysSpeed, body.forwardSpeed);
        owner.probe.input = owner.input; owner.probe.ticksToNextAutoJump = 0;
    }
    static void end(FabricRollbackSimulatedPlayer body) {
        var owner = ACTIVE.get(); if (owner == null || owner.player != body) return;
        if (!owner.frame) throw new IllegalStateException("Missing native player tick");
        owner.observed = new Decision(body.age, owner.probe.ticksToNextAutoJump > 0); owner.frame = false;
    }
    static void moved(FabricRollbackSimulatedPlayer body, float dx, float dz) {
        var owner = ACTIVE.get(); if (owner == null || owner.player != body || !owner.frame || !owner.enabled) return;
        owner.probe.pos = body.getEntityPos(); owner.probe.dimensions = body.dimensions;
        owner.probe.setBoundingBox(body.getBoundingBox()); owner.reading = true;
        try { owner.probe.autoJump(dx, dz); }
        finally { owner.reading = false; }
    }
    /** Preserve the real owned entity for collision membership and all native shape-context reads. */
    public static Entity collisionSource(Entity queried) {
        var owner = ACTIVE.get();
        return owner != null && owner.reading && queried == owner.probe ? owner.player : queried;
    }
    @SuppressWarnings("unchecked") private static RegistryEntry<StatusEffect> effect(Object value) { return (RegistryEntry<StatusEffect>) value; }
    private void check() { if (Thread.currentThread() != thread) throw new IllegalStateException("Auto-jump crossed threads"); }
    private static final class Movement extends Input {
        void set(float sideways, float forward) { movementVector = new Vec2f(sideways, forward); }
    }
}

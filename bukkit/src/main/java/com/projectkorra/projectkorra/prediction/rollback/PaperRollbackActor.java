package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.*;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** A private spawned native entity and its ability-facing view share one rewindable body. */
final class PaperRollbackActor implements RollbackActorRegistry.Actor<RollbackStateGraph.Snapshot>, KinematicsSource<RollbackStateGraph.Snapshot>, ActorProperties {
    private final PaperRollbackNativeScene scene;
    private final net.minecraft.world.entity.Entity nativeActor;
    private final Identity identity;
    private final RollbackStateGraph state;
    private final Entity view;

    <T extends Entity> PaperRollbackActor(PaperRollbackNativeScene scene, net.minecraft.world.entity.Entity actor,
            Identity identity, RollbackEntityBody.Rules rules, Class<T> type, RollbackTypedEntity.Specialized specialized) {
        this.scene = Objects.requireNonNull(scene); this.nativeActor = Objects.requireNonNull(actor); this.identity = Objects.requireNonNull(identity);
        if (actor.level() != scene.world().world() || !actor.getUUID().equals(identity.uuid()) || actor.getId() != identity.networkId()
                || actor instanceof net.minecraft.world.entity.player.Player || actor.valid || actor.inWorld)
            throw new IllegalArgumentException("Actor belongs to a different native world or tracker");
        var body = RollbackEntityBody.nativeBacked(identity, scene.logical(), this, rules);
        view = type == Entity.class ? new RollbackEntity(body) : RollbackTypedEntity.dispatch(type, body, specialized, this);
        state = new RollbackStateGraph(value -> scene.world().checkpointExternal(nativeActor, value) || PaperRollbackMetadata.frozen(value),
                field -> true, RollbackDuelRuntime.GRAPH.maximumObjects(), scene.world()::checkpointReferent);
    }
    @Override public Identity identity() { return identity; }
    @Override public Entity view() { return view; }
    net.minecraft.world.entity.Entity entity() { return nativeActor; }

    @Override public boolean valid() { return !nativeActor.isRemoved(); }
    @Override public boolean dead() { return !nativeActor.isAlive(); }
    @Override public void remove() {
        if (nativeActor.isRemoved()) return;
        if (nativeActor.valid || nativeActor.inWorld || !scene.world().ownsEntity(nativeActor))
            throw new IllegalStateException("Cannot retire an unowned native actor");
        scene.world().event(new org.bukkit.event.entity.EntityRemoveEvent(nativeActor.getBukkitEntity(),
                org.bukkit.event.entity.EntityRemoveEvent.Cause.PLUGIN));
        PaperRollbackPrivateAccess.retirePrivateActor(nativeActor);
    }
    @Override public int fireTicks() { return nativeActor.getRemainingFireTicks(); }
    @Override public void fireTicks(int value) { nativeActor.setRemainingFireTicks(value); }
    @Override public boolean silent() { return nativeActor.isSilent(); }
    @Override public void silent(boolean value) { nativeActor.setSilent(value); }
    @Override public boolean invulnerable() { return nativeActor.isInvulnerable(); }
    @Override public void invulnerable(boolean value) { nativeActor.setInvulnerable(value); }
    @Override public boolean gravity() { return !nativeActor.isNoGravity(); }
    @Override public void gravity(boolean value) { nativeActor.setNoGravity(!value); }

    @Override public Kinematics readKinematics() {
        var p = nativeActor.position(); var b = nativeActor.getBoundingBox(); var v = nativeActor.getDeltaMovement();
        return new Kinematics(new Pose(p.x, p.y, p.z, nativeActor.getYRot(), nativeActor.getXRot()),
                new Motion(v.x, v.y, v.z), new RollbackBlockStore.Box(b.minX - p.x, b.minY - p.y, b.minZ - p.z,
                b.maxX - p.x, b.maxY - p.y, b.maxZ - p.z), nativeActor.getBbHeight(), nativeActor.onGround(), nativeActor.fallDistance, nativeActor.hurtMarked);
    }
    @Override public void writeKinematics(Kinematics next) {
        var current = readKinematics();
        if (!current.bounds().equals(next.bounds()) || current.height() != next.height())
            throw new IllegalArgumentException("Native entity dimensions must change through its pose");
        var p = next.pose(); var v = next.velocity();
        if (!current.pose().equals(p)) nativeActor.snapTo(p.x(), p.y(), p.z(), p.yaw(), p.pitch());
        if (!current.velocity().equals(v)) nativeActor.setDeltaMovement(v.x(), v.y(), v.z());
        if (current.onGround() != next.onGround()) nativeActor.setOnGround(next.onGround());
        nativeActor.fallDistance = next.fallDistance(); nativeActor.hurtMarked = next.velocityChanged();
    }
    @Override public double eyeHeight() { return nativeActor.getEyeHeight(); }
    @Override public void tick() {
        if (!RollbackClock.active() || !RollbackDomain.active()) throw new IllegalStateException("Native actor tick outside its match");
        if (!nativeActor.isRemoved() && !nativeActor.isPassenger()) {
            nativeActor.setOldPosAndRot();
            nativeActor.tickCount++; nativeActor.totalEntityAge++;
            nativeActor.tick();
            for (var passenger : nativeActor.getPassengers()) {
                if (scene.world().ownsEntity(passenger)) nativeActor.positionRider(passenger);
            }
        }
        RollbackEntityBody.logicalBody(view).liveness(!nativeActor.isRemoved(), !nativeActor.isAlive());
    }

    @Override public byte[] exportState() { return exportState(nativeActor, identity); }

    static byte[] exportState(net.minecraft.world.entity.Entity nativeActor, Identity identity) {
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, nativeActor.level().registryAccess());
        nativeActor.saveWithoutId(output, false, false, false);
        output.store("UUID", UUIDUtil.CODEC, identity.uuid());
        output.store("Pos", Vec3.CODEC, nativeActor.position());
        output.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(nativeActor.getType()).toString());
        var old = nativeActor.oldPosition();
        return new RollbackActorFrame(nativeActor.tickCount, new Pose(old.x, old.y, old.z, nativeActor.yRotO, nativeActor.xRotO),
                nativeActor.horizontalCollision, nativeActor.verticalCollision, nativeActor.verticalCollisionBelow, nativeActor.needsSync,
                nativeActor.hurtMarked, PaperRollbackRandomStreams.capture(nativeActor.getRandom()), PaperRollbackBlockSnapshots.encode(output.buildResult())).encode();
    }
    void importState(byte[] bytes) {
        restore(nativeActor, identity, bytes, identity.networkId());
    }
    /** Live trackers allocate a fresh positive network ID; stable UUID references remain valid. */
    static void restore(net.minecraft.world.entity.Entity nativeActor, Identity identity, byte[] bytes, int networkId) {
        var frame = RollbackActorFrame.decode(bytes);
        var tag = PaperRollbackBlockSnapshots.decode(frame.nbt());
        var input = TagValueInput.create(ProblemReporter.DISCARDING, nativeActor.level().registryAccess(), tag);
        if (!identity.uuid().equals(input.read("UUID", UUIDUtil.CODEC).orElseThrow())
                || !BuiltInRegistries.ENTITY_TYPE.getKey(nativeActor.getType()).toString().equals(input.getStringOr("id", "")))
            throw new IllegalArgumentException("Native actor checkpoint identity differs");
        nativeActor.load(input); nativeActor.setId(networkId);
        var old = frame.last();
        nativeActor.setOldPosAndRot(new Vec3(old.x(), old.y(), old.z()), old.yaw(), old.pitch());
        nativeActor.tickCount = frame.age(); nativeActor.totalEntityAge = frame.age(); nativeActor.horizontalCollision = frame.horizontalCollision();
        nativeActor.verticalCollision = frame.verticalCollision(); nativeActor.verticalCollisionBelow = frame.groundCollision();
        nativeActor.needsSync = frame.impulse(); nativeActor.hurtMarked = frame.sendVelocity();
        PaperRollbackRandomStreams.restore(nativeActor.getRandom(), frame.random());
    }
    @Override public RollbackStateGraph.Snapshot captureRollbackState() { return state.capture(List.of(nativeActor), List.of()); }
    @Override public void restoreRollbackState(RollbackStateGraph.Snapshot saved) { saved.restore(); }

    @Override public Collection<?> rollbackReferences() { return List.of(RollbackEntityBody.logicalBody(view)); }
}

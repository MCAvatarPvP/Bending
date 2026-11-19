package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.EntityRollbackControlAccess;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import java.io.*;
import java.util.*;

/** An owned spawned Minecraft entity and the exact typed facade used by ability code. */
final class FabricRollbackActor implements RollbackActorRegistry.Actor<RollbackStateGraph.Snapshot>,
        RollbackEntityBody.KinematicsSource<RollbackStateGraph.Snapshot>, RollbackEntityBody.ActorProperties {
    private final Entity nativeEntity;
    private final Identity identity;
    private final FabricRollbackPaperRandom random;
    private final RollbackStateGraph graph;
    private final RollbackEntityBody body;
    private final com.projectkorra.projectkorra.platform.mc.entity.Entity view;
    private final int lifespan;

    <T extends com.projectkorra.projectkorra.platform.mc.entity.Entity> FabricRollbackActor(
            Entity entity, Identity identity, RollbackWorld world, RollbackEntityBody.Rules policy, int lifespan, Class<T> type, RollbackTypedEntity.Specialized specialized) {
        this.lifespan = lifespan;
        this.nativeEntity = Objects.requireNonNull(entity); this.identity = Objects.requireNonNull(identity);
        if (entity instanceof PlayerEntity) throw new IllegalArgumentException("Actor cannot own a participant");
        entity.setUuid(identity.uuid()); entity.setId(identity.networkId());
        random = new FabricRollbackPaperRandom(identity.uuid().getMostSignificantBits() ^ identity.uuid().getLeastSignificantBits());
        ((EntityRollbackControlAccess) entity).rollback$random(random);
        graph = new RollbackStateGraph(value -> value == nativeEntity.getEntityWorld() || value instanceof Entity other && other != nativeEntity
                || FabricRollbackNativePlayerState.registryMetadata(value) || value instanceof net.minecraft.block.Block
                || value instanceof net.minecraft.entity.attribute.DefaultAttributeContainer, FabricRollbackNativePlayerState::simulationField, 65_536);
        body = RollbackEntityBody.nativeBacked(identity, world, this, policy);
        view = type == com.projectkorra.projectkorra.platform.mc.entity.Entity.class ? new RollbackEntity(body)
                : RollbackTypedEntity.dispatch(type, body, specialized, this);
    }

    Entity owned() { return nativeEntity; }
    @Override public com.projectkorra.projectkorra.platform.mc.entity.Entity view() { return view; }
    @Override public boolean valid() { return !nativeEntity.isRemoved(); }
    @Override public boolean dead() { return !nativeEntity.isAlive(); }
    @Override public void remove() { nativeEntity.discard(); }
    @Override public int fireTicks() { return nativeEntity.getFireTicks(); }
    @Override public void fireTicks(int value) { nativeEntity.setFireTicks(value); }
    @Override public boolean silent() { return nativeEntity.isSilent(); }
    @Override public void silent(boolean value) { nativeEntity.setSilent(value); }
    @Override public boolean invulnerable() { return nativeEntity.isInvulnerable(); }
    @Override public void invulnerable(boolean value) { nativeEntity.setInvulnerable(value); }
    @Override public boolean gravity() { return !nativeEntity.hasNoGravity(); }
    @Override public void gravity(boolean value) { nativeEntity.setNoGravity(!value); }
    @Override public Identity identity() { return identity; }
    @Override public Kinematics readKinematics() {
        var e = nativeEntity; var box = e.getBoundingBox(); var v = e.getVelocity();
        double x = e.getX(), y = e.getY(), z = e.getZ();
        return new Kinematics(new Pose(x, y, z, e.getYaw(), e.getPitch()), new Motion(v.x, v.y, v.z),
                new RollbackBlockStore.Box(box.minX-x, box.minY-y, box.minZ-z, box.maxX-x, box.maxY-y, box.maxZ-z),
                e.getHeight(), e.isOnGround(), e.fallDistance, e.knockedBack);
    }
    @Override public void writeKinematics(Kinematics value) {
        var previous = readKinematics(); var e = nativeEntity;
        if (!previous.bounds().equals(value.bounds()) || previous.height() != value.height())
            throw new IllegalArgumentException("Private actor dimensions follow its native pose");
        var p = value.pose(); var v = value.velocity();
        if (!p.equals(previous.pose())) e.refreshPositionAndAngles(p.x(), p.y(), p.z(), p.yaw(), p.pitch());
        if (!v.equals(previous.velocity())) e.setVelocity(v.x(), v.y(), v.z());
        if (value.onGround() != previous.onGround()) e.setOnGround(value.onGround());
        e.fallDistance = value.fallDistance(); e.knockedBack = value.velocityChanged();
    }
    @Override public double eyeHeight() { return nativeEntity.getEyeHeight(nativeEntity.getPose()); }
    @Override public void tick() {
        if (body.valid() && !nativeEntity.hasVehicle() && !nativeEntity.isRemoved()) {
            if (lifespan >= 0 && nativeEntity.age + 1 >= lifespan) nativeEntity.discard();
            else ((ServerWorld) nativeEntity.getEntityWorld()).tickEntity(nativeEntity);
        }
        body.liveness(!nativeEntity.isRemoved(), !nativeEntity.isAlive());
    }
    @Override public RollbackStateGraph.Snapshot captureRollbackState() {
        return graph.capture(List.of(nativeEntity), List.of());
    }
    @Override public void restoreRollbackState(RollbackStateGraph.Snapshot snapshot) { snapshot.restore(); }
    @Override public Collection<?> rollbackReferences() { return List.of(body); }

    @Override public byte[] exportState() {
        var e = nativeEntity;
        var errors = new ErrorReporter.Impl();
        var write = NbtWriteView.create(errors, e.getEntityWorld().getRegistryManager());
        e.writeData(write);
        if (!errors.isEmpty()) throw new IllegalStateException("Cannot serialize native actor " + identity);
        var tag = write.getNbt(); tag.putString("id", Registries.ENTITY_TYPE.getId(e.getType()).toString());
        var buffer = new RollbackItemData.Output();
        try (var out = new DataOutputStream(buffer)) { NbtIo.writeCompound(tag, out); }
        catch (IOException failure) { throw new UncheckedIOException(failure); }
        return new RollbackActorFrame(e.age, new Pose(e.lastX, e.lastY, e.lastZ, e.lastYaw, e.lastPitch),
                e.horizontalCollision, e.verticalCollision, e.groundCollision, e.velocityDirty, e.knockedBack,
                random.exportStream(), buffer.toByteArray()).encode();
    }

    void importState(byte[] bytes) {
        var frame = RollbackActorFrame.decode(bytes);
        var e = nativeEntity;
        try (var in = new DataInputStream(new ByteArrayInputStream(frame.nbt()))) {
            var tag = NbtIo.readCompound(in, new NbtSizeTracker(4L << 20, 64));
            if (in.available() != 0 || !tag.getString("id").orElseThrow().equals(Registries.ENTITY_TYPE.getId(e.getType()).toString()))
                throw new IllegalArgumentException("Private actor NBT type or length changed");
            var errors = new ErrorReporter.Impl();
            e.readData(NbtReadView.create(errors, e.getEntityWorld().getRegistryManager(), tag));
            if (!errors.isEmpty() || !e.getUuid().equals(identity.uuid())) throw new IllegalArgumentException("Invalid native actor data");
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid actor NBT", failure); }
        var p = frame.last();
        e.lastX = p.x(); e.lastY = p.y(); e.lastZ = p.z(); e.lastYaw = p.yaw(); e.lastPitch = p.pitch();
        e.age = frame.age(); e.horizontalCollision = frame.horizontalCollision(); e.verticalCollision = frame.verticalCollision();
        e.groundCollision = frame.groundCollision(); e.velocityDirty = frame.impulse(); e.knockedBack = frame.sendVelocity();
        random.restoreStream(frame.random());
    }
}

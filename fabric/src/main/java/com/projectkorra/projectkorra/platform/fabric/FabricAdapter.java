package com.projectkorra.projectkorra.platform.fabric;

import com.projectkorra.projectkorra.fabric.prediction.PredictionServer;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.model.*;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * Native Minecraft object adapter used by the loader-neutral model API.
 */
public final class FabricAdapter implements PKAdapter {
    @Override
    public PKPlayer player(Object value) {
        return new PlayerView((ServerPlayerEntity) value);
    }

    @Override
    public PKEntity entity(Object value) {
        return value instanceof ServerPlayerEntity player ? player(player) : value instanceof LivingEntity living ? livingEntity(living) : new EntityView((Entity) value);
    }

    @Override
    public PKLivingEntity livingEntity(Object value) {
        return value instanceof ServerPlayerEntity player ? player(player) : new LivingView((LivingEntity) value);
    }

    @Override
    public PKWorld world(Object value) {
        return new WorldView((ServerWorld) value);
    }

    @Override
    public PKBlock block(Object value) {
        FabricMC.BlockRef ref = (FabricMC.BlockRef) value;
        return new BlockView(ref.world(), ref.pos());
    }

    @Override
    public PKLocation location(Object value) {
        FabricMC.LocationRef ref = (FabricMC.LocationRef) value;
        return new LocationView(ref.world(), ref.pos(), ref.yaw(), ref.pitch());
    }

    @Override
    public Object unwrap(Object value) {
        return value instanceof PKHandle handle ? handle.handle() : value;
    }

    private record WorldView(ServerWorld value) implements PKWorld {
        @Override
        public Object handle() {
            return value;
        }

        @Override
        public String name() {
            return value.getRegistryKey().getValue().toString();
        }

        @Override
        public PKBlock blockAt(int x, int y, int z) {
            return new BlockView(value, new BlockPos(x, y, z));
        }

        @Override
        public void playSound(PKLocation location, String sound, float volume, float pitch) {
            if (location == null) return;
            FabricMC.playConfigSound(value, location.x(), location.y(), location.z(), sound, SoundCategory.MASTER, volume, pitch);
        }

        @Override
        public void spawnParticle(String particle, PKLocation location, int count, double x, double y, double z, double extra) {
            if (location == null) return;
            ParticleEffect effect = FabricMC.particle(Particle.valueOf(particle), null);
            ServerPlayerEntity predictedOwner = PredictionServer.predictedEffectOwner();
            for (ServerPlayerEntity viewer : value.getPlayers()) {
                if (viewer == predictedOwner) continue;
                value.spawnParticles(viewer, effect, false, false, location.x(), location.y(), location.z(), count, x, y, z, extra);
            }
        }
    }

    private record BlockView(ServerWorld value, BlockPos pos) implements PKBlock {
        @Override
        public Object handle() {
            return new FabricMC.BlockRef(value, pos);
        }

        @Override
        public PKWorld world() {
            return new WorldView(value);
        }

        @Override
        public int x() {
            return pos.getX();
        }

        @Override
        public int y() {
            return pos.getY();
        }

        @Override
        public int z() {
            return pos.getZ();
        }

        @Override
        public String materialKey() {
            return Registries.BLOCK.getId(value.getBlockState(pos).getBlock()).toString();
        }

        @Override
        public boolean liquid() {
            return !value.getFluidState(pos).isEmpty();
        }

        @Override
        public boolean passable() {
            return value.getBlockState(pos).getCollisionShape(value, pos).isEmpty();
        }

        @Override
        public PKLocation location() {
            return new LocationView(value, Vec3d.of(pos), 0, 0);
        }
    }

    private record LocationView(ServerWorld value, Vec3d pos, float yaw, float pitch) implements PKLocation {
        @Override
        public Object handle() {
            return new FabricMC.LocationRef(value, pos, yaw, pitch);
        }

        @Override
        public PKWorld world() {
            return new WorldView(value);
        }

        @Override
        public double x() {
            return pos.x;
        }

        @Override
        public double y() {
            return pos.y;
        }

        @Override
        public double z() {
            return pos.z;
        }

        @Override
        public PKVec3 direction() {
            double ry = Math.toRadians(yaw), rp = Math.toRadians(pitch);
            double cp = Math.cos(rp);
            return new PKVec3(-Math.sin(ry) * cp, -Math.sin(rp), Math.cos(ry) * cp);
        }
    }

    private record EntityView(Entity value) implements PKEntity {
        @Override
        public Object handle() {
            return value;
        }

        @Override
        public UUID uuid() {
            return value.getUuid();
        }

        @Override
        public PKWorld world() {
            return new WorldView((ServerWorld) value.getEntityWorld());
        }

        @Override
        public PKLocation location() {
            return new LocationView((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch());
        }

        @Override
        public PKVec3 velocity() {
            Vec3d v = value.getVelocity();
            return new PKVec3(v.x, v.y, v.z);
        }

        @Override
        public void velocity(PKVec3 v) {
            value.setVelocity(v.x(), v.y(), v.z());
        }

        @Override
        public boolean valid() {
            return !value.isRemoved();
        }

        @Override
        public void remove() {
            value.discard();
        }
    }

    private record LivingView(LivingEntity value) implements PKLivingEntity {
        @Override
        public Object handle() {
            return value;
        }

        @Override
        public UUID uuid() {
            return value.getUuid();
        }

        @Override
        public PKWorld world() {
            return new WorldView((ServerWorld) value.getEntityWorld());
        }

        @Override
        public PKLocation location() {
            return new LocationView((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch());
        }

        @Override
        public PKVec3 velocity() {
            Vec3d v = value.getVelocity();
            return new PKVec3(v.x, v.y, v.z);
        }

        @Override
        public void velocity(PKVec3 v) {
            value.setVelocity(v.x(), v.y(), v.z());
        }

        @Override
        public boolean valid() {
            return !value.isRemoved();
        }

        @Override
        public void remove() {
            value.discard();
        }

        @Override
        public double health() {
            return value.getHealth();
        }

        @Override
        public void damage(double amount, PKEntity source) {
            value.damage((ServerWorld) value.getEntityWorld(), value.getDamageSources().generic(), (float) amount);
        }
    }

    private record PlayerView(ServerPlayerEntity value) implements PKPlayer {
        @Override
        public Object handle() {
            return value;
        }

        @Override
        public String name() {
            return value.getName().getString();
        }

        @Override
        public UUID uuid() {
            return value.getUuid();
        }

        @Override
        public PKWorld world() {
            return new WorldView((ServerWorld) value.getEntityWorld());
        }

        @Override
        public PKLocation location() {
            return new LocationView((ServerWorld) value.getEntityWorld(), value.getEntityPos(), value.getYaw(), value.getPitch());
        }

        @Override
        public PKVec3 velocity() {
            Vec3d v = value.getVelocity();
            return new PKVec3(v.x, v.y, v.z);
        }

        @Override
        public void velocity(PKVec3 v) {
            value.setVelocity(v.x(), v.y(), v.z());
        }

        @Override
        public boolean valid() {
            return !value.isRemoved();
        }

        @Override
        public void remove() {
            value.discard();
        }

        @Override
        public double health() {
            return value.getHealth();
        }

        @Override
        public void damage(double amount, PKEntity source) {
            value.damage((ServerWorld) value.getEntityWorld(), value.getDamageSources().generic(), (float) amount);
        }

        @Override
        public void sendMessage(String message) {
            value.sendMessage(Text.literal(message));
        }

        @Override
        public boolean hasPermission(String permission) {
            return FabricMC.player(value).hasPermission(permission);
        }

        @Override
        public boolean sneaking() {
            return value.isSneaking();
        }

        @Override
        public boolean sprinting() {
            return value.isSprinting();
        }

        @Override
        public PKLocation eyeLocation() {
            return new LocationView((ServerWorld) value.getEntityWorld(), value.getEyePos(), value.getYaw(), value.getPitch());
        }

        @Override
        public PKBlock targetBlock(int range) {
            HitResult hit = value.raycast(range, 0, false);
            return hit instanceof BlockHitResult block ? new BlockView((ServerWorld) value.getEntityWorld(), block.getBlockPos()) : null;
        }
    }
}

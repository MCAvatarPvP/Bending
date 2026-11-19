package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.AreaEffectCloudEntityAccessor;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Vec3d;
import java.util.*;

/** Runs vanilla cloud emission for a detached frame, keeping the actual particles selected by the client. */
public final class FabricRollbackCloudParticles extends AreaEffectCloudEntity {
    record Frame(UUID actor, Vec3d position, float radius, boolean waiting, NbtElement particle) {
        static Frame capture(AreaEffectCloudEntity cloud) {
            return new Frame(cloud.getUuid(), cloud.getEntityPos(), cloud.getRadius(), cloud.isWaiting(),
                    ParticleTypes.TYPE_CODEC.encodeStart(cloud.getEntityWorld().getRegistryManager().getOps(NbtOps.INSTANCE),
                            cloud.getParticleType()).getOrThrow());
        }
    }

    private static final ThreadLocal<List<Particle>> DRAWING = new ThreadLocal<>();

    FabricRollbackCloudParticles(ClientWorld world) { super(EntityType.AREA_EFFECT_CLOUD, world); }

    /** Called at the vanilla client world's particle factory result, after distance and quality checks. */
    public static Particle created(Particle particle) {
        if (particle != null) {
            var handles = DRAWING.get();
            if (handles != null) handles.add(particle);
        }
        return particle;
    }

    List<Particle> draw(long tick, Frame frame) {
        if (getEntityWorld().getEntity(frame.actor()) != null) return List.of();
        setPosition(frame.position());
        getDataTracker().set(AreaEffectCloudEntityAccessor.projectkorra$radius(), frame.radius());
        setWaiting(frame.waiting());
        setParticleType(ParticleTypes.TYPE_CODEC.parse(getEntityWorld().getRegistryManager().getOps(NbtOps.INSTANCE), frame.particle()).getOrThrow());
        getRandom().setSeed(frame.actor().getLeastSignificantBits() ^ frame.actor().getMostSignificantBits() ^ tick);
        var handles = new ArrayList<Particle>();
        if (DRAWING.get() != null) throw new IllegalStateException("Nested cloud rendering");
        DRAWING.set(handles);
        try {
            ((AreaEffectCloudEntityAccessor)(Object)this).projectkorra$clientTick();
            return handles;
        } catch (RuntimeException | Error failure) {
            handles.forEach(Particle::markDead);
            throw failure;
        } finally {
            DRAWING.remove();
        }
    }
}

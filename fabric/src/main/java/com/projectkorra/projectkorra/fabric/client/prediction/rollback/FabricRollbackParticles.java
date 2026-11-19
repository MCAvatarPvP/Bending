package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import java.util.*;

/** Presents each reconciled particle once and lets confirmed particles finish their normal lifetime. */
final class FabricRollbackParticles implements RollbackClientRuntime.Output<RollbackDomain.Checkpoint, Object> {
    private final MinecraftClient client;
    private final FabricRollbackNativeScene.Duel duel;
    private final UUID viewer;
    private final RollbackReplicaTimeline<RollbackDomain.Checkpoint, RollbackPlayerInput, Object> timeline;
    private final FabricRollbackParticleHistory<Particle> history;
    private long head = -1;
    FabricRollbackParticles(MinecraftClient client, FabricRollbackNativeScene.Duel duel,
            RollbackReplicaTimeline<RollbackDomain.Checkpoint, RollbackPlayerInput, Object> timeline) {
        this.client = client;
        this.duel = duel;
        this.timeline = timeline;
        viewer = client.player.getUuid();
        history = new FabricRollbackParticleHistory<>(viewer, this::draw, Particle::markDead);
    }
    @Override public void update(RollbackEngine.Update<RollbackDomain.Checkpoint, RollbackPlayerInput, Object> update) {
        long tick = update.head().tick();
        if (!com.projectkorra.projectkorra.fabric.client.config.ClientBendingConfig.isVisualsEnabled()) {
            history.clear();
            head = tick;
            return;
        }
        history.confirm(update.confirmed().tick(), update.finalizedEffects());
        long first = update.replayedFrom() < 0 ? head + 1 : Math.min(head + 1, update.replayedFrom());
        first = Math.max(first, update.confirmed().tick() + 1);
        if (first == tick) history.frame(tick, update.head().effects());
        else if (first < tick) {
            for (var frame : timeline.frames(first, tick)) history.frame(frame.tick(), frame.effects());
        }
        head = tick;
    }
    private List<Particle> draw(long tick, int ordinal, RollbackArenaActions.ParticleOutput event, int attempts) {
        var particle = FabricMC.particle(com.projectkorra.projectkorra.platform.mc.Particle.valueOf(event.particle()), payload(event.data()));
        if (particle == null) return List.of();
        var rng = new Random(viewer.getLeastSignificantBits() ^ tick * 31 + ordinal);
        var drawn = new ArrayList<Particle>();
        var pos = event.position();
        int count = event.count();
        for (int i = 0; i < attempts; i++) {
            double x = pos.x(), y = pos.y(), z = pos.z();
            double dx = event.x(), dy = event.y(), dz = event.z();
            if (count != 0) {
                x += rng.nextGaussian() * dx; y += rng.nextGaussian() * dy; z += rng.nextGaussian() * dz;
                dx = rng.nextGaussian(); dy = rng.nextGaussian(); dz = rng.nextGaussian();
            }
            var visual = client.particleManager.addParticle(particle, x, y, z, dx * event.extra(), dy * event.extra(), dz * event.extra());
            if (visual != null) drawn.add(visual);
        }
        return drawn;
    }
    private Object payload(Object data) {
        return switch (data) {
            case null -> null;
            case RollbackArenaActions.BlockValue b -> new FabricRollbackTerrainTransfer().decode(b.material(), b.state());
            case RollbackArenaActions.ColorValue c -> Color.fromRGB(c.rgb());
            case RollbackArenaActions.DustValue dust -> new com.projectkorra.projectkorra.platform.mc.Particle.DustOptions(Color.fromRGB(dust.rgb()), dust.size());
            case RollbackArenaActions.SpellValue spell -> new com.projectkorra.projectkorra.platform.mc.Particle.Spell(Color.fromRGB(spell.rgb()), spell.power());
            case RollbackItemData item -> duel.items().capture(new FabricRollbackItemCodec(client.world.getRegistryManager()).decode(item));
            default -> data;
        };
    }
    @Override public void stop(RollbackStartServerEndpoint.Failure failure) { history.clear(); }
}

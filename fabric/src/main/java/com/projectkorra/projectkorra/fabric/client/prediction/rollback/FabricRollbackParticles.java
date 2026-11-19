package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.platform.fabric.FabricMC;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.particle.Particle;
import java.util.*;

/** Keeps provisional particles removable until their tick is finalized and Paper supplies the authoritative presentation. */
final class FabricRollbackParticles implements RollbackClientRuntime.Output<RollbackDomain.Checkpoint, Object> {
    private final MinecraftClient client;
    private final FabricRollbackNativeScene.Duel duel;
    private final UUID viewer;
    private final NavigableMap<Long, List<Particle>> provisional = new TreeMap<>();
    private long head = -1, revision = -1;
    FabricRollbackParticles(MinecraftClient client, FabricRollbackNativeScene.Duel duel) {
        this.client = client; this.duel = duel; viewer = client.player.getUuid();
    }
    @Override public void update(RollbackEngine.Update<RollbackDomain.Checkpoint, RollbackPlayerInput, Object> update) {
        if (update.replayedFrom() >= 0) clear(provisional.tailMap(update.replayedFrom(), true));
        clear(provisional.headMap(update.confirmed().tick(), true));
        long tick = update.head().tick();
        if (tick <= update.confirmed().tick() || tick == head && revision == update.revision()) return;
        head = tick; revision = update.revision();
        clear(provisional.subMap(tick, true, tick, true));
        var drawn = new ArrayList<Particle>();
        provisional.put(tick, drawn);
        var random = new Random(tick ^ viewer.getLeastSignificantBits());
        int remaining = 16_384;
        for (Object item : update.head().effects()) {
            if (remaining == 0) break;
            if (item instanceof RollbackArenaActions.ParticleOutput effect && (effect.viewer() == null || effect.viewer().equals(viewer)))
                remaining -= draw(effect, random, drawn, remaining);
        }
    }
    private int draw(RollbackArenaActions.ParticleOutput event, Random rng, List<Particle> drawn, int remaining) {
        var particle = FabricMC.particle(com.projectkorra.projectkorra.platform.mc.Particle.valueOf(event.particle()), payload(event.data()));
        if (particle == null) return 0;
        var pos = event.position();
        int count = event.count();
        int attempts = Math.min(remaining, Math.max(1, count));
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
        return attempts;
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
    private void clear(Map<Long, List<Particle>> range) {
        range.values().forEach(effects -> effects.forEach(Particle::markDead));
        range.clear();
    }
    @Override public void stop(RollbackStartServerEndpoint.Failure failure) { clear(provisional); }
}

package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.attribute.*;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Native dimension, biome, timeline and weather layers evaluated only against the owned logical world. */
public final class PaperRollbackEnvironment implements EnvironmentAttributeReader, RollbackStateCell<PaperRollbackEnvironment.Snapshot> {
    public static final class Snapshot {
        private final PaperRollbackEnvironment owner;
        private final RollbackEnvironmentData.Weather weather;
        private Snapshot(PaperRollbackEnvironment owner) { this.owner = owner; weather = owner.weather; }
    }
    private final Thread thread = Thread.currentThread();
    private final RollbackWorld logical;
    private final RollbackEnvironmentData seed;
    private final DimensionType dimension;
    private final Registry<Biome> biomes;
    private RollbackEnvironmentData.Weather weather;
    private EnvironmentAttributeSystem evaluator;
    private long cachedDay;

    public static RollbackEnvironmentData capture(ServerLevel world) {
        requireSetup();
        return new RollbackEnvironmentData(world.dimensionTypeRegistration().unwrapKey().orElseThrow().identifier().toString(),
                world.canHaveWeather(), new RollbackEnvironmentData.Weather(world.getRainLevel(1), world.getThunderLevel(1)));
    }
    public PaperRollbackEnvironment(RollbackWorld logical, RegistryAccess.Frozen registries, RollbackEnvironmentData seed) {
        requireSetup(); this.logical = Objects.requireNonNull(logical); this.seed = Objects.requireNonNull(seed); weather = seed.weather();
        dimension = registries.lookupOrThrow(Registries.DIMENSION_TYPE).get(Identifier.parse(seed.dimensionType()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown captured dimension type")).value();
        biomes = registries.lookupOrThrow(Registries.BIOME); rebuild();
    }
    public void weather(RollbackEnvironmentData.Weather weather) { checkThread(); this.weather = Objects.requireNonNull(weather); evaluator.invalidateTickCache(); }
    public RollbackEnvironmentData.Weather weather() { checkThread(); return weather; }
    @Override public <T> T getDimensionValue(EnvironmentAttribute<T> attribute) { sync(); return evaluator.getDimensionValue(attribute); }
    @Override public <T> T getValue(EnvironmentAttribute<T> attribute, Vec3 position, SpatialAttributeInterpolator interpolation) {
        sync(); cell(position);
        if (interpolation != null && (!(interpolation instanceof Interpolation owned) || owned.owner != this)) throw new IllegalArgumentException("Foreign attribute interpolation");
        return evaluator.getValue(attribute, position, interpolation);
    }
    /** A detached, immutable weighted view over this session's frozen biome definitions. */
    public SpatialAttributeInterpolator interpolation(Map<String, Double> weights) { checkThread(); return new Interpolation(weights); }
    private final class Interpolation extends SpatialAttributeInterpolator implements RollbackStateCell<Void> {
        private final PaperRollbackEnvironment owner = PaperRollbackEnvironment.this;
        private Interpolation(Map<String, Double> weights) {
            if (weights.size() > 256) throw new IllegalArgumentException("Biome interpolation budget");
            double total = 0;
            for (var entry : new TreeMap<>(weights).entrySet()) {
                double weight = entry.getValue(); total += weight;
                if (!Double.isFinite(weight) || weight <= 0 || !Double.isFinite(total) || total > Float.MAX_VALUE) throw new IllegalArgumentException("Biome interpolation weight");
                super.accumulate(weight, biome(entry.getKey()).getAttributes());
            }
        }
        @Override public void clear() { throw new UnsupportedOperationException("Private interpolation is immutable"); }
        @Override public SpatialAttributeInterpolator accumulate(double weight, EnvironmentAttributeMap attributes) { throw new UnsupportedOperationException("Private interpolation is immutable"); }
        @Override public Void captureRollbackState() { checkThread(); return null; }
        @Override public void restoreRollbackState(Void ignored) { checkThread(); }
        @Override public List<?> rollbackReferences() { checkThread(); return List.of(owner); }
    }
    private void rebuild() {
        var builder = EnvironmentAttributeSystem.builder().addConstantLayer(dimension.attributes());
        biomes.stream().flatMap(biome -> biome.getAttributes().keySet().stream()).distinct().forEach(attribute -> biomeLayer(builder, attribute));
        dimension.timelines().forEach(timeline -> builder.addTimelineLayer(timeline, logical::getFullTime));
        if (seed.weatherEnabled()) WeatherAttributes.addBuiltinLayers(builder, new WeatherAttributes.WeatherAccess() {
            @Override public float rainLevel() { return weather.rain(); }
            @Override public float thunderLevel() { return weather.thunder(); }
        });
        evaluator = builder.build(); cachedDay = logical.getFullTime();
    }
    private <T> void biomeLayer(EnvironmentAttributeSystem.Builder builder, EnvironmentAttribute<T> attribute) {
        builder.addPositionalLayer(attribute, (base, position, interpolation) -> interpolation != null && attribute.isSpatiallyInterpolated()
                ? interpolation.applyAttributeLayer(attribute, base) : biome(cell(position).noiseBiomeKey()).getAttributes().applyModifier(attribute, base));
    }
    private Biome biome(String key) { return biomes.get(Identifier.parse(key)).orElseThrow(() -> new IllegalArgumentException("Unknown captured noise biome: " + key)).value(); }
    private RollbackBlockStore.Cell cell(Vec3 position) {
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || position.x < Integer.MIN_VALUE || position.x > Integer.MAX_VALUE || position.y < Integer.MIN_VALUE || position.y > Integer.MAX_VALUE
                || position.z < Integer.MIN_VALUE || position.z > Integer.MAX_VALUE) throw new IllegalArgumentException("Environment position");
        return logical.terrain().cell(new RollbackBlockStore.Position((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z)));
    }
    private void sync() { checkThread(); long day = logical.getFullTime(); if (day != cachedDay) { evaluator.invalidateTickCache(); cachedDay = day; } }
    @Override public Snapshot captureRollbackState() { checkThread(); return new Snapshot(this); }
    @Override public void restoreRollbackState(Snapshot snapshot) { checkThread(); if (snapshot.owner != this) throw new IllegalArgumentException("Foreign environment checkpoint"); weather = snapshot.weather; rebuild(); }
    @Override public List<?> rollbackReferences() { checkThread(); return List.of(logical); }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Private environment crossed threads"); }
    private static void requireSetup() { if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Prepare native environment before replay"); }
}

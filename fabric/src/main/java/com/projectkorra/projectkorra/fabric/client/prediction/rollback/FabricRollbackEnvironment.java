package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.attribute.*;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.dimension.DimensionType;

import java.util.*;

/** Native environment layers over captured terrain and time; no client-world fallbacks. */
public final class FabricRollbackEnvironment implements EnvironmentAttributeAccess, RollbackStateCell<FabricRollbackEnvironment.Snapshot> {
    public static final class Snapshot {
        private final FabricRollbackEnvironment owner;
        private final RollbackEnvironmentData.Weather weather;
        private Snapshot(FabricRollbackEnvironment owner) { this.owner = owner; weather = owner.weather; }
    }
    private final Thread thread = Thread.currentThread();
    private final RollbackWorld logical;
    private final RollbackEnvironmentData seed;
    private final DimensionType dimension;
    private final Registry<Biome> biomes;
    private RollbackEnvironmentData.Weather weather;
    private WorldEnvironmentAttributeAccess evaluator;
    private long cachedDay;

    public FabricRollbackEnvironment(RollbackWorld logical, DynamicRegistryManager.Immutable registries, RollbackEnvironmentData seed) {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Prepare native environment before replay");
        this.logical = Objects.requireNonNull(logical); this.seed = Objects.requireNonNull(seed); weather = seed.weather();
        dimension = registries.getOrThrow(RegistryKeys.DIMENSION_TYPE).getEntry(Identifier.of(seed.dimensionType()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown captured dimension type")).value();
        biomes = registries.getOrThrow(RegistryKeys.BIOME); rebuild();
    }
    public void weather(RollbackEnvironmentData.Weather weather) { checkThread(); this.weather = Objects.requireNonNull(weather); evaluator.tick(); }
    public RollbackEnvironmentData.Weather weather() { checkThread(); return weather; }
    @Override public <T> T getAttributeValue(EnvironmentAttribute<T> attribute) { sync(); return evaluator.getAttributeValue(attribute); }
    @Override public <T> T getAttributeValue(EnvironmentAttribute<T> attribute, Vec3d position, WeightedAttributeList interpolation) {
        sync(); cell(position);
        if (interpolation != null && (!(interpolation instanceof Interpolation owned) || owned.owner != this)) throw new IllegalArgumentException("Foreign attribute interpolation");
        return evaluator.getAttributeValue(attribute, position, interpolation);
    }
    public WeightedAttributeList interpolation(Map<String, Double> weights) { checkThread(); return new Interpolation(weights); }
    private final class Interpolation extends WeightedAttributeList implements RollbackStateCell<Void> {
        private final FabricRollbackEnvironment owner = FabricRollbackEnvironment.this;
        private Interpolation(Map<String, Double> weights) {
            if (weights.size() > 256) throw new IllegalArgumentException("Biome interpolation budget");
            double total = 0;
            for (var entry : new TreeMap<>(weights).entrySet()) {
                double weight = entry.getValue(); total += weight;
                if (!Double.isFinite(weight) || weight <= 0 || !Double.isFinite(total) || total > Float.MAX_VALUE) throw new IllegalArgumentException("Biome interpolation weight");
                super.add(weight, biome(entry.getKey()).getEnvironmentAttributes());
            }
        }
        @Override public void clear() { throw new UnsupportedOperationException("Private interpolation is immutable"); }
        @Override public WeightedAttributeList add(double weight, EnvironmentAttributeMap attributes) { throw new UnsupportedOperationException("Private interpolation is immutable"); }
        @Override public Void captureRollbackState() { checkThread(); return null; }
        @Override public void restoreRollbackState(Void ignored) { checkThread(); }
        @Override public List<?> rollbackReferences() { checkThread(); return List.of(owner); }
    }
    private void rebuild() {
        var builder = WorldEnvironmentAttributeAccess.builder().addFromMap(dimension.attributes());
        biomes.stream().flatMap(biome -> biome.getEnvironmentAttributes().keySet().stream()).distinct().forEach(attribute -> biomeLayer(builder, attribute));
        dimension.timelines().forEach(timeline -> builder.addFromTimeline(timeline, logical::getFullTime));
        if (seed.weatherEnabled()) WeatherAttributes.addWeatherAttributes(builder, new WeatherAttributes.WeatherAccess() {
            @Override public float getRainGradient() { return weather.rain(); }
            @Override public float getThunderGradient() { return weather.thunder(); }
        });
        evaluator = builder.build(); cachedDay = logical.getFullTime();
    }
    private <T> void biomeLayer(WorldEnvironmentAttributeAccess.Builder builder, EnvironmentAttribute<T> attribute) {
        builder.positional(attribute, (base, position, interpolation) -> interpolation != null && attribute.isInterpolated()
                ? interpolation.interpolate(attribute, base) : biome(cell(position).noiseBiomeKey()).getEnvironmentAttributes().apply(attribute, base));
    }
    private Biome biome(String key) { return biomes.getEntry(Identifier.of(key)).orElseThrow(() -> new IllegalArgumentException("Unknown captured noise biome: " + key)).value(); }
    private RollbackBlockStore.Cell cell(Vec3d position) {
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)
                || position.x < Integer.MIN_VALUE || position.x > Integer.MAX_VALUE || position.y < Integer.MIN_VALUE || position.y > Integer.MAX_VALUE
                || position.z < Integer.MIN_VALUE || position.z > Integer.MAX_VALUE) throw new IllegalArgumentException("Environment position");
        return logical.terrain().cell(new RollbackBlockStore.Position((int) Math.floor(position.x), (int) Math.floor(position.y), (int) Math.floor(position.z)));
    }
    private void sync() { checkThread(); long day = logical.getFullTime(); if (day != cachedDay) { evaluator.tick(); cachedDay = day; } }
    @Override public Snapshot captureRollbackState() { checkThread(); return new Snapshot(this); }
    @Override public void restoreRollbackState(Snapshot snapshot) { checkThread(); if (snapshot.owner != this) throw new IllegalArgumentException("Foreign environment checkpoint"); weather = snapshot.weather; rebuild(); }
    @Override public List<?> rollbackReferences() { checkThread(); return List.of(logical); }
    private void checkThread() { if (thread != Thread.currentThread()) throw new IllegalStateException("Private environment crossed threads"); }
}

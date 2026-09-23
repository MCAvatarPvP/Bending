package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeQueryShell;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stat;
import net.minecraft.stats.StatType;
import net.minecraft.stats.Stats;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.player.Player;
import org.spigotmc.SpigotConfig;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Owned native statistic counters/events; disk saving and connection output stay unavailable. */
public final class PaperRollbackStatistics implements RollbackStateCell<PaperRollbackStatistics.Checkpoint> {
    /** Captured configuration and imported counter values. Native forced values override imported values. */
    public record Seed(boolean disabled, Map<Identifier, Integer> forced, Map<Stat<?>, Integer> values) {
        public Seed {
            forced = Map.copyOf(forced); values = Map.copyOf(values);
            for (var key : forced.keySet()) {
                if (!BuiltInRegistries.CUSTOM_STAT.containsKey(key)) throw new IllegalArgumentException("Unknown forced native statistic: " + key);
            }
            values.keySet().forEach(PaperRollbackStatistics::requireStat);
        }

        /** New-player state; capture global settings only during session setup. */
        public static Seed fresh() {
            if (RollbackClock.active()) throw new IllegalStateException("Capture statistic settings before replay");
            return new Seed(SpigotConfig.disableStatSaving, SpigotConfig.forcedStats, Map.of());
        }
    }

    private final Thread thread = Thread.currentThread();
    private final Supplier<ServerPlayer> owner;
    private final Object2IntMap<Stat<?>> values = Object2IntMaps.synchronize(new Object2IntOpenHashMap<>());
    private final Set<Stat<?>> dirty = new HashSet<>();
    private final ServerStatsCounter counter;
    private final MethodHandle increment, set;

    PaperRollbackStatistics(PaperRollbackWorldAccess world, Supplier<ServerPlayer> owner, Seed seed) {
        if (RollbackClock.active()) throw new IllegalStateException("Construct native statistics before replay");
        this.owner = Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(seed, "seed");
        values.defaultReturnValue(0);
        values.putAll(seed.values());
        seed.forced().forEach((key, value) -> values.put(Stats.CUSTOM.get(BuiltInRegistries.CUSTOM_STAT.getValue(key)), value.intValue()));
        var methods = new RollbackNativeMethods();
        new PaperRollbackNativeEvents(world::event, world::ownsWrapper).bind(methods);
        try {
            var incrementMethod = StatsCounter.class.getMethod("increment", Player.class, Stat.class, int.class);
            var setMethod = ServerStatsCounter.class.getDeclaredMethod("setValue", Player.class, Stat.class, int.class);
            methods.copy(incrementMethod).copy(setMethod)
                    .read(SpigotConfig.class.getField("disableStatSaving"), MethodHandles.constant(boolean.class, seed.disabled()))
                    .read(SpigotConfig.class.getField("forcedStats"), MethodHandles.constant(Map.class, seed.forced()));
            var copied = methods.build();
            increment = copied.get(incrementMethod); set = copied.get(setMethod);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Native statistics methods changed", failure); }
        counter = RollbackNativeQueryShell.create(ServerStatsCounter.class)
                .nativeQuery(value -> value.getValue(Stats.CUSTOM.get(Stats.JUMP)), 0, args -> { check(); requireStat((Stat<?>) args[0]); })
                .nativeQuery(value -> value.getValue(Stats.CUSTOM, Stats.JUMP), 0, args -> { check(); requireTypeValue((StatType<?>) args[0], args[1]); })
                .outputQuery(value -> value.increment(null, null, 0), args -> increment((Player) args[0], (Stat<?>) args[1], (int) args[2]))
                .outputQuery(value -> value.setValue(null, null, 0), args -> set((Player) args[0], (Stat<?>) args[1], (int) args[2]))
                .nativeAction(ServerStatsCounter::markAllDirty, args -> check())
                .instance();
        PaperRollbackPrivateAccess.initializeStatistics(counter, values, dirty);
    }

    ServerStatsCounter counter() { check(); return counter; }

    /** Detached pending values for the eventual private connection-output adapter. Does not acknowledge them. */
    Map<Stat<?>, Integer> pendingValues() {
        check();
        var pending = new java.util.HashMap<Stat<?>, Integer>();
        for (var stat : dirty) pending.put(stat, values.getInt(stat));
        return Map.copyOf(pending);
    }

    private void increment(Player player, Stat<?> stat, int amount) {
        requirePlayer(player, false); requireStat(stat);
        try { increment.invokeExact((StatsCounter) counter, player, stat, amount); }
        catch (Throwable failure) { throw failed(failure); }
    }

    private void set(Player player, Stat<?> stat, int value) {
        // CraftBukkit's explicit statistic setters pass null; the counter itself
        // supplies ownership. Event-producing increments require the exact player.
        requirePlayer(player, true); requireStat(stat);
        try { set.invokeExact(counter, player, stat, value); }
        catch (Throwable failure) { throw failed(failure); }
    }

    private void requirePlayer(Player player, boolean nullable) {
        check();
        if (player == null && nullable) return;
        if (player != owner.get()) throw new IllegalArgumentException("Statistic update belongs to another player");
    }

    @SuppressWarnings({"rawtypes", "unchecked", "WrapperReferenceEquality"})
    static void requireStat(Stat<?> stat) {
        Objects.requireNonNull(stat, "statistic");
        requireTypeValue(stat.getType(), stat.getValue());
        if (((StatType) stat.getType()).get(stat.getValue()) != stat) throw new IllegalArgumentException("Noncanonical native statistic");
    }

    @SuppressWarnings({"rawtypes", "unchecked", "WrapperReferenceEquality"})
    private static void requireTypeValue(StatType<?> type, Object value) {
        Objects.requireNonNull(type, "statistic type");
        var key = BuiltInRegistries.STAT_TYPE.getResourceKey(type).orElseThrow(() -> new IllegalArgumentException("Foreign statistic type"));
        if (BuiltInRegistries.STAT_TYPE.getValue(key) != type || ((net.minecraft.core.Registry) type.getRegistry()).getResourceKey(value).isEmpty()) {
            throw new IllegalArgumentException("Foreign statistic registry value");
        }
    }

    private void check() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native statistics crossed threads");
    }

    @Override public Checkpoint captureRollbackState() { check(); return new Checkpoint(this, Map.copyOf(values), Set.copyOf(dirty)); }
    @Override public void restoreRollbackState(Checkpoint saved) {
        check();
        if (Objects.requireNonNull(saved, "checkpoint").owner != this) throw new IllegalArgumentException("Statistics checkpoint belongs to another player");
        values.clear(); values.putAll(saved.values); dirty.clear(); dirty.addAll(saved.dirty);
    }

    public static final class Checkpoint {
        private final PaperRollbackStatistics owner;
        private final Map<Stat<?>, Integer> values;
        private final Set<Stat<?>> dirty;
        private Checkpoint(PaperRollbackStatistics owner, Map<Stat<?>, Integer> values, Set<Stat<?>> dirty) {
            this.owner = owner; this.values = values; this.dirty = dirty;
        }
    }

    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Native statistic update failed", failure);
    }
}

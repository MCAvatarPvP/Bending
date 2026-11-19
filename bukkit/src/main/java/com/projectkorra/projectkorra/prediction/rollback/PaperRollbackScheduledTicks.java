package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackScheduledTicks.Entry;
import com.projectkorra.projectkorra.prediction.rollback.RollbackScheduledTicks.Kind;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.ticks.*;

import java.util.*;
import java.util.function.BiConsumer;

/** Per-chunk vanilla queues use the same ordering, duplicates and phase limits as the server. */
final class PaperRollbackScheduledTicks implements RollbackStateCell<RollbackScheduledTicks> {
    private final Thread thread = Thread.currentThread();
    private final RollbackScheduledTicks seed;
    private final ServerLevel world;
    private Queues<Block> blocks;
    private Queues<Fluid> fluids;
    private long nextOrder;

    private static final class Queues<T> {
        final Registry<T> registry;
        final Kind kind;
        final LevelTicks<T> nativeTicks = new LevelTicks<>(chunk -> true);
        final Map<ChunkPos, LevelChunkTicks<T>> chunks = new LinkedHashMap<>();
        Queues(Registry<T> registry, Kind kind, Bounds b) {
            this.registry = registry; this.kind = kind;
            for (int x = b.minX() >> 4; x <= (b.maxX() - 1) >> 4; x++) {
                for (int z = b.minZ() >> 4; z <= (b.maxZ() - 1) >> 4; z++) {
                    var pos = new ChunkPos(x, z);
                    var container = new LevelChunkTicks<T>();
                    chunks.put(pos, container);
                    nativeTicks.addContainer(pos, container);
                }
            }
        }
        void add(Entry entry) {
            var type = registry.get(Identifier.parse(entry.id())).orElseThrow(() -> new IllegalArgumentException("Unknown scheduled tick type " + entry.id())).value();
            var p = entry.position();
            nativeTicks.schedule(new ScheduledTick<>(type, new BlockPos(p.x(), p.y(), p.z()), entry.time(),
                    TickPriority.byValue(entry.priority()), entry.order()));
        }
        void collect(List<Entry> result) {
            for (var container : chunks.values()) {
                container.getAll().forEach(tick -> {
                    var p = tick.pos();
                    result.add(new Entry(kind, new Position(p.getX(), p.getY(), p.getZ()),
                            registry.getKey(tick.type()).toString(), tick.triggerTick(), tick.priority().getValue(), tick.subTickOrder()));
                });
            }
        }
    }
    PaperRollbackScheduledTicks(ServerLevel world, RollbackScheduledTicks seed) {
        this.world = world; this.seed = seed;
        restoreRollbackState(seed);
    }
    LevelTicks<Block> blocks() { check(); return blocks.nativeTicks; }
    LevelTicks<Fluid> fluids() { check(); return fluids.nativeTicks; }

    void schedule(BlockPos pos, Object type, int delay, TickPriority priority) {
        check();
        if (!seed.bounds().contains(new Position(pos.getX(), pos.getY(), pos.getZ())))
            throw new IllegalStateException("Native tick scheduled outside the owned arena");
        long when = Math.addExact(world.getGameTime(), delay);
        long order = nextOrder; nextOrder = Math.incrementExact(nextOrder);
        if (type instanceof Block block) {
            if (!blocks.nativeTicks.hasScheduledTick(pos, block)) checkCapacity();
            blocks.nativeTicks.schedule(new ScheduledTick<>(block, pos.immutable(), when, priority, order));
        } else if (type instanceof Fluid fluid) {
            if (!fluids.nativeTicks.hasScheduledTick(pos, fluid)) checkCapacity();
            fluids.nativeTicks.schedule(new ScheduledTick<>(fluid, pos.immutable(), when, priority, order));
        } else throw new IllegalArgumentException("Unknown native tick kind");
    }
    private void checkCapacity() {
        if ((long) blocks.nativeTicks.count() + fluids.nativeTicks.count() >= RollbackScheduledTicks.MAXIMUM_ENTRIES)
            throw new IllegalStateException("Scheduled terrain tick budget exceeded");
    }

    void advance(long time) {
        check();
        blocks.nativeTicks.tick(time, seed.maximumBlockTicks(), (pos, block) -> {
            var state = world.getBlockState(pos);
            if (state.is(block)) state.tick(world, pos, world.getRandom());
        });
        fluids.nativeTicks.tick(time, seed.maximumFluidTicks(), (pos, fluid) -> {
            var state = world.getBlockState(pos);
            var water = state.getFluidState();
            if (water.getType() == fluid) water.tick(world, pos, state);
        });
    }
    @Override public RollbackScheduledTicks captureRollbackState() {
        check(); var entries = new ArrayList<Entry>();
        blocks.collect(entries); fluids.collect(entries);
        return new RollbackScheduledTicks(seed.bounds(), seed.maximumBlockTicks(), seed.maximumFluidTicks(), nextOrder, entries);
    }
    @Override public void restoreRollbackState(RollbackScheduledTicks state) {
        check();
        if (!state.bounds().equals(seed.bounds()) || state.maximumBlockTicks() != seed.maximumBlockTicks()
                || state.maximumFluidTicks() != seed.maximumFluidTicks()) throw new IllegalArgumentException("Foreign scheduled terrain region");
        var newBlocks = new Queues<>(BuiltInRegistries.BLOCK, Kind.BLOCK, seed.bounds());
        var newFluids = new Queues<>(BuiltInRegistries.FLUID, Kind.FLUID, seed.bounds());
        for (var e : state.entries()) {
            if (e.kind() == Kind.BLOCK) newBlocks.add(e);
            else newFluids.add(e);
        }
        blocks = newBlocks; fluids = newFluids; nextOrder = state.nextOrder();
    }
    private void check() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native scheduled ticks crossed threads");
    }
}

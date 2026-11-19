package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.RollbackScheduledTicks;

import com.projectkorra.projectkorra.prediction.rollback.RollbackScheduledTicks.Entry;
import com.projectkorra.projectkorra.prediction.rollback.RollbackScheduledTicks.Kind;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.block.Block;
import net.minecraft.fluid.Fluid;
import net.minecraft.world.tick.*;

import java.util.*;
import java.util.function.BiConsumer;

/** Per-chunk vanilla queues use the same ordering, duplicates and phase limits as the server. */
final class FabricRollbackScheduledTicks implements RollbackStateCell<RollbackScheduledTicks> {
    private final Thread thread = Thread.currentThread();
    private final RollbackScheduledTicks seed;
    private final ServerWorld world;
    private Queues<Block> blocks;
    private Queues<Fluid> fluids;
    private long nextOrder;

    private static final class Queues<T> {
        final Registry<T> registry;
        final Kind kind;
        final WorldTickScheduler<T> nativeTicks = new WorldTickScheduler<>(chunk -> true);
        final Map<ChunkPos, ChunkTickScheduler<T>> chunks = new LinkedHashMap<>();
        Queues(Registry<T> registry, Kind kind, Bounds b) {
            this.registry = registry; this.kind = kind;
            for (int x = b.minX() >> 4; x <= (b.maxX() - 1) >> 4; x++) {
                for (int z = b.minZ() >> 4; z <= (b.maxZ() - 1) >> 4; z++) {
                    var pos = new ChunkPos(x, z);
                    var container = new ChunkTickScheduler<T>();
                    chunks.put(pos, container);
                    nativeTicks.addChunkTickScheduler(pos, container);
                }
            }
        }
        void add(Entry entry) {
            var type = registry.getEntry(Identifier.of(entry.id())).orElseThrow(() -> new IllegalArgumentException("Unknown scheduled tick type " + entry.id())).value();
            var p = entry.position();
            nativeTicks.scheduleTick(new OrderedTick<>(type, new BlockPos(p.x(), p.y(), p.z()), entry.time(),
                    TickPriority.byIndex(entry.priority()), entry.order()));
        }
        void collect(List<Entry> result) {
            for (var container : chunks.values()) {
                container.getQueueAsStream().forEach(tick -> {
                    var p = tick.pos();
                    result.add(new Entry(kind, new Position(p.getX(), p.getY(), p.getZ()),
                            registry.getId(tick.type()).toString(), tick.triggerTick(), tick.priority().getIndex(), tick.subTickOrder()));
                });
            }
        }
    }
    FabricRollbackScheduledTicks(ServerWorld world, RollbackScheduledTicks seed) {
        this.world = world; this.seed = seed;
        restoreRollbackState(seed);
    }
    WorldTickScheduler<Block> blocks() { check(); return blocks.nativeTicks; }
    WorldTickScheduler<Fluid> fluids() { check(); return fluids.nativeTicks; }

    void schedule(BlockPos pos, Object type, int delay, TickPriority priority) {
        check();
        if (!seed.bounds().contains(new Position(pos.getX(), pos.getY(), pos.getZ())))
            throw new IllegalStateException("Native tick scheduled outside the owned arena");
        long when = Math.addExact(world.getTime(), delay);
        long order = nextOrder; nextOrder = Math.incrementExact(nextOrder);
        if (type instanceof Block block) {
            if (!blocks.nativeTicks.isQueued(pos, block)) checkCapacity();
            blocks.nativeTicks.scheduleTick(new OrderedTick<>(block, pos.toImmutable(), when, priority, order));
        } else if (type instanceof Fluid fluid) {
            if (!fluids.nativeTicks.isQueued(pos, fluid)) checkCapacity();
            fluids.nativeTicks.scheduleTick(new OrderedTick<>(fluid, pos.toImmutable(), when, priority, order));
        } else throw new IllegalArgumentException("Unknown native tick kind");
    }
    private void checkCapacity() {
        if ((long) blocks.nativeTicks.getTickCount() + fluids.nativeTicks.getTickCount() >= RollbackScheduledTicks.MAXIMUM_ENTRIES)
            throw new IllegalStateException("Scheduled terrain tick budget exceeded");
    }

    void advance(long time) {
        check();
        blocks.nativeTicks.tick(time, seed.maximumBlockTicks(), (pos, block) -> {
            var state = world.getBlockState(pos);
            if (state.isOf(block)) state.scheduledTick(world, pos, world.getRandom());
        });
        fluids.nativeTicks.tick(time, seed.maximumFluidTicks(), (pos, fluid) -> {
            var state = world.getBlockState(pos);
            var water = state.getFluidState();
            if (water.getFluid() == fluid) water.onScheduledTick(world, pos, state);
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
        var newBlocks = new Queues<>(Registries.BLOCK, Kind.BLOCK, seed.bounds());
        var newFluids = new Queues<>(Registries.FLUID, Kind.FLUID, seed.bounds());
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

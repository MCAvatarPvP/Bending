package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.RollbackScheduledTicks.Entry;
import com.projectkorra.projectkorra.prediction.rollback.RollbackScheduledTicks.Kind;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.ticks.*;

import java.util.*;

/** Owns the live queued work inside one duel without removing scheduled updates in its lighting margin. */
final class PaperRollbackLiveScheduledTicks {
    private record Key(Kind kind, Position pos, String id) { }
    private static final Map<ServerLevel, List<PaperRollbackLiveScheduledTicks>> LEASES = new IdentityHashMap<>();
    private final ServerLevel world;
    private final RollbackScheduledTicks original;
    private final long acquiredTime;
    private final Set<Key> committed = new HashSet<>();
    private RollbackScheduledTicks outgoing;
    private long frontier;
    private boolean attached, released;

    private PaperRollbackLiveScheduledTicks(ServerLevel world, RollbackScheduledTicks original, long acquiredTime) {
        this.world = world; this.original = original; this.acquiredTime = acquiredTime;
        this.outgoing = original; this.frontier = acquiredTime;
    }
    static RollbackScheduledTicks capture(ServerLevel world, Bounds b) {
        boundary();
        if (world.isHandlingTick()) throw new IllegalStateException("Capture scheduled terrain between native world ticks");
        var entries = new ArrayList<Entry>();
        for (int x = b.minX() >> 4; x <= (b.maxX() - 1) >> 4; x++) {
            for (int z = b.minZ() >> 4; z <= (b.maxZ() - 1) >> 4; z++) {
                var chunk = world.getChunkSource().getChunkAtIfLoadedImmediately(x, z);
                if (chunk == null) throw new IllegalStateException("Scheduled tick chunk is not loaded");
                collect(entries, b, Kind.BLOCK, BuiltInRegistries.BLOCK, (LevelChunkTicks<net.minecraft.world.level.block.Block>) chunk.getBlockTicks());
                collect(entries, b, Kind.FLUID, BuiltInRegistries.FLUID, (LevelChunkTicks<net.minecraft.world.level.material.Fluid>) chunk.getFluidTicks());
            }
        }
        long next = Math.incrementExact(entries.stream().mapToLong(Entry::order).max().orElse(-1));
        var config = world.paperConfig().environment;
        return new RollbackScheduledTicks(b, config.maxBlockTicks, config.maxFluidTicks, next, entries);
    }
    private static <T> void collect(List<Entry> entries, Bounds bounds, Kind kind, Registry<T> registry, LevelChunkTicks<T> chunk) {
        // Unpacked ticks are the only runnable native representation. A chunk pending load cannot be leased.
        if (chunk.getAll().count() != chunk.count()) throw new IllegalStateException("Chunk still has unpacked scheduled work pending");
        chunk.getAll().forEach(tick -> {
            var p = tick.pos(); var pos = new Position(p.getX(), p.getY(), p.getZ());
            if (bounds.contains(pos)) {
                if (entries.size() >= RollbackScheduledTicks.MAXIMUM_ENTRIES) throw new IllegalArgumentException("Too many live scheduled ticks");
                entries.add(new Entry(kind, pos, registry.getKey(tick.type()).toString(), tick.triggerTick(), tick.priority().getValue(), tick.subTickOrder()));
            }
        });
    }
    static PaperRollbackLiveScheduledTicks prepare(ServerLevel world, RollbackWorldSeed seed) {
        boundary(); Objects.requireNonNull(seed.scheduledTicks(), "Captured native tick queues");
        if (!world.getWorld().getUID().equals(seed.world()) || world.getGameTime() != seed.settings().gameTime()
                || !capture(world, seed.scheduledTicks().bounds()).equals(seed.scheduledTicks()))
            throw new IllegalStateException("Live scheduled terrain changed during capture");
        return new PaperRollbackLiveScheduledTicks(world, seed.scheduledTicks(), world.getGameTime());
    }
    void acquire() {
        boundary();
        if (attached || released) throw new IllegalStateException("Scheduled terrain already claimed");
        var leases = LEASES.computeIfAbsent(world, ignored -> new ArrayList<>());
        for (var existing : leases) {
            if (overlaps(existing.original.bounds(), original.bounds())) throw new IllegalStateException("Duel scheduled terrain overlaps an active match");
        }
        leases.add(this); attached = true;
        var b = original.bounds();
        var nativeArea = new BoundingBox(b.minX(), b.minY(), b.minZ(), b.maxX() - 1, b.maxY() - 1, b.maxZ() - 1);
        world.getBlockTicks().clearArea(nativeArea);
        world.getFluidTicks().clearArea(nativeArea);
    }
    private static boolean overlaps(Bounds a, Bounds b) {
        return a.minX() < b.maxX() && b.minX() < a.maxX() && a.minY() < b.maxY() && b.minY() < a.maxY()
                && a.minZ() < b.maxZ() && b.minZ() < a.maxZ();
    }
    void stage(RollbackScheduledTicks state, long time) {
        boundary();
        if (!original.bounds().equals(state.bounds()) || time < acquiredTime || !committed.isEmpty() && !state.equals(outgoing))
            throw new IllegalArgumentException("Foreign scheduled terrain handoff");
        outgoing = state; frontier = time;
    }
    void restore() {
        boundary();
        if (released || !attached) return;
        long now = world.getGameTime();
        var existing = new HashMap<Key, Entry>();
        for (var entry : capture(world, original.bounds()).entries()) existing.put(key(entry), entry);
        for (var entry : outgoing.entries()) {
            var current = existing.get(key(entry));
            if (current != null && !committed.contains(key(entry))
                    && (current.time() != targetTime(entry, now) || current.priority() != entry.priority()))
                throw new IllegalStateException("Live scheduled tick changed at " + entry.position());
            requireType(entry);
        }
        for (var entry : outgoing.entries()) {
            var key = key(entry);
            if (committed.contains(key) || existing.containsKey(key)) continue;
            var p = entry.position(); var pos = new BlockPos(p.x(), p.y(), p.z());
            var priority = TickPriority.byValue(entry.priority()); long order = world.nextSubTickCount();
            long when = targetTime(entry, now);
            if (entry.kind() == Kind.BLOCK) {
                var type = BuiltInRegistries.BLOCK.get(Identifier.parse(entry.id())).orElseThrow().value();
                world.getBlockTicks().schedule(new ScheduledTick<>(type, pos, when, priority, order));
            } else {
                var type = BuiltInRegistries.FLUID.get(Identifier.parse(entry.id())).orElseThrow().value();
                world.getFluidTicks().schedule(new ScheduledTick<>(type, pos, when, priority, order));
            }
            committed.add(key);
        }
        var owners = LEASES.get(world);
        owners.remove(this);
        if (owners.isEmpty()) LEASES.remove(world);
        released = true;
    }
    private long targetTime(Entry entry, long now) { return Math.addExact(now, Math.max(0, Math.subtractExact(entry.time(), frontier))); }
    private static Key key(Entry entry) { return new Key(entry.kind(), entry.position(), entry.id()); }
    private static void requireType(Entry entry) {
        var id = Identifier.parse(entry.id());
        if ((entry.kind() == Kind.BLOCK ? BuiltInRegistries.BLOCK.get(id) : BuiltInRegistries.FLUID.get(id)).isEmpty())
            throw new IllegalArgumentException("Unknown outgoing scheduled terrain type " + id);
    }
    private static void boundary() {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Live scheduled terrain requires the tick-thread handoff boundary");
    }
}

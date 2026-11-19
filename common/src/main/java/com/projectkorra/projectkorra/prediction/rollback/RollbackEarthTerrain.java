package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.earthbending.RaiseEarth;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.util.Information;
import com.projectkorra.projectkorra.util.RevertChecker;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/** The captured area's moved-earth, temporary-air, structure and pending reversion indexes. */
public final class RollbackEarthTerrain {
    public final HashSet<Block> prevented = new LinkedHashSet<>();
    public final ArrayList<Block> physics = new ArrayList<>();
    public final Map<Block, Information> moved = new LinkedHashMap<>();
    public final Map<Integer, Information> air = new LinkedHashMap<>();
    public final Map<Block, Block> earthQueue = new LinkedHashMap<>();
    public final Map<Integer, Integer> airQueue = new LinkedHashMap<>();
    public final ConcurrentHashMap<RaiseEarth.BlockKey, Integer> walls = new ConcurrentHashMap<>(), columns = new ConcurrentHashMap<>();
    public final ConcurrentHashMap<Block, Integer> active = new ConcurrentHashMap<>();
    private int next, limit;
    private long nextRevertCheck = -1;

    public void revertCheckDelay(long ticks) {
        if (ticks < -1 || ticks > 200) throw new IllegalArgumentException("Invalid private earth reversion delay");
        nextRevertCheck = ticks;
    }

    /** Reproduce the server's periodic due-time and occupied-chunk selection only in this private arena. */
    public void scanDue(long tick) {
        if (tick != nextRevertCheck) return;
        nextRevertCheck = Math.addExact(nextRevertCheck, 200);
        var config = com.projectkorra.projectkorra.configuration.ConfigManager.defaultConfig.get();
        if (!config.getBoolean("Properties.Earth.RevertEarthbending")) return;
        long delay = config.getLong("Properties.Earth.RevertCheckTime");
        var occupied = new HashSet<Long>();
        if (config.getBoolean("Properties.Earth.SafeRevert")) com.projectkorra.projectkorra.BendingPlayer.getPlayers().values().forEach(profile -> {
            var pos = profile.getPlayer().getLocation();
            occupied.add(chunk(pos.getBlockX(), pos.getBlockZ()));
        });
        long now = RollbackClock.millis();
        moved.forEach((block, info) -> {
            if (now > info.getTime() + delay && !occupied.contains(chunk(block.getX(), block.getZ()))) earthQueue.putIfAbsent(block, block);
        });
        air.forEach((id, info) -> {
            var block = info.getBlock();
            if (now > info.getTime() + delay && !occupied.contains(chunk(block.getX(), block.getZ()))) airQueue.putIfAbsent(id, id);
        });
    }
    private static long chunk(int blockX, int blockZ) {
        return ((long)(blockX >> 4) << 32) | Integer.toUnsignedLong(blockZ >> 4);
    }

    public static RollbackEarthTerrain current() {
        var layers = RollbackAbilityGlobals.layers();
        return layers == null ? null : layers.earth();
    }
    public int nextId() {
        if (next >= limit) throw new IllegalStateException("Private moved-earth ID reservation exhausted");
        return next++;
    }
    public static RollbackEarthTerrain capture(Predicate<Block> inside, String world, Bounds bounds, Set<UUID> players) {
        var seed = select(inside, world, bounds, players);
        seed.next = Information.reserveRollbackIds(65_536);
        seed.limit = Math.addExact(seed.next, 65_536);
        return seed;
    }
    private static RollbackEarthTerrain select(Predicate<Block> inside, String world, Bounds bounds, Set<UUID> players) {
        var seed = new RollbackEarthTerrain();
        for (var block : EarthAbility.getPreventEarthbendingBlocks()) if (inside.test(block)) seed.prevented.add(block);
        for (var block : EarthAbility.getPreventPhysicsBlocks()) if (inside.test(block)) seed.physics.add(block);
        EarthAbility.getMovedEarth().forEach((target, info) -> {
            var owner = owner(info);
            if (inside.test(target)) {
                if (owner == null || !players.contains(owner)) throw new IllegalStateException("Moved earth in the arena has no enrolled owner");
                seed.moved.put(target, info);
            } else if (owner != null && players.contains(owner)) throw new IllegalStateException("Participant moved earth beyond the arena");
        });
        EarthAbility.getTempAirLocations().forEach((id, info) -> {
            var owner = owner(info);
            if (inside.test(info.getBlock())) {
                if (owner == null || !players.contains(owner)) throw new IllegalStateException("Temporary air in the arena has no enrolled owner");
                seed.air.put(id, info);
            } else if (owner != null && players.contains(owner)) throw new IllegalStateException("Participant has temporary air beyond the arena");
        });
        RevertChecker.earthQueue().forEach((block, value) -> { if (inside.test(block)) seed.earthQueue.put(block, value); });
        RevertChecker.airQueue().forEach((id, value) -> { if (seed.air.containsKey(id)) seed.airQueue.put(id, value); });
        RaiseEarth.walls().forEach((key, refs) -> { if (key.inside(world, bounds)) seed.walls.put(key, refs); });
        RaiseEarth.columns().forEach((key, refs) -> { if (key.inside(world, bounds)) seed.columns.put(key, refs); });
        RaiseEarth.activeBlocks().forEach((block, refs) -> { if (inside.test(block)) seed.active.put(block, refs); });
        seed.validate(inside, world, bounds, players);
        // Persistent eligibility markers without an owned earth record cannot be attributed to this roster.
        var owned = new HashSet<>(seed.moved.keySet());
        seed.air.values().forEach(info -> owned.add(info.getBlock()));
        var coords = new HashSet<RaiseEarth.BlockKey>();
        owned.forEach(block -> coords.add(RaiseEarth.BlockKey.of(block)));
        if (!owned.containsAll(seed.prevented) || !owned.containsAll(seed.physics) || !owned.containsAll(seed.active.keySet())
                || !coords.containsAll(seed.walls.keySet()) || !coords.containsAll(seed.columns.keySet()) || !owned.containsAll(seed.earthQueue.keySet()))
            throw new IllegalStateException("Unattributed earth structure intersects the arena");
        return seed;
    }
    public void requireCurrent(Predicate<Block> inside, String world, Bounds bounds, Set<UUID> players) {
        var live = select(inside, world, bounds, players);
        if (!moved.equals(live.moved) || !air.equals(live.air) || !earthQueue.equals(live.earthQueue)
                || !airQueue.equals(live.airQueue) || !walls.equals(live.walls) || !columns.equals(live.columns)
                || !active.equals(live.active) || !prevented.equals(live.prevented) || !physics.equals(live.physics))
            throw new IllegalStateException("Earth ownership changed before duel enrollment");
    }
    private static UUID owner(Information info) { return info.getPredictionOwner() != null ? info.getPredictionOwner() : info.getPlayer() == null ? null : info.getPlayer().getUniqueId(); }

    private void validate(Predicate<Block> inside, String world, Bounds bounds, Set<UUID> players) {
        for (var group : List.of(prevented, physics, moved.keySet(), earthQueue.keySet(), earthQueue.values(), active.keySet()))
            for (var block : group) if (!inside.test(block)) throw new IllegalStateException("Earth structure crosses the arena");
        for (var records : List.of(moved.values(), air.values())) for (var info : records) {
            var owner = owner(info);
            if (info.getBlock() == null || !inside.test(info.getBlock()) || info.getState() == null || !inside.test(info.getState().getBlock())
                    || owner != null && !players.contains(owner)) throw new IllegalStateException("Earth restoration source crosses duel ownership");
        }
        for (var index : List.of(walls, columns)) index.forEach((key, refs) -> {
            if (refs < 1 || !key.inside(world, bounds)) throw new IllegalStateException("Invalid raised-earth structure");
        });
        air.forEach((id, info) -> { if (id != info.getID()) throw new IllegalStateException("Invalid temporary-air identity"); });
        airQueue.forEach((key, value) -> { if (!key.equals(value) || !air.containsKey(key)) throw new IllegalStateException("Unknown queued air restore"); });
    }
    public void validateSettlement(RollbackEarthTerrain original, Predicate<Block> inside, String world, Bounds bounds, Set<UUID> players) {
        if (limit != original.limit || next < original.next || next > limit) throw new IllegalStateException("Invalid earth ID reservation");
        validate(inside, world, bounds, players);
        if (!Collections.disjoint(moved.keySet(), EarthAbility.getMovedEarth().keySet()) || !Collections.disjoint(air.keySet(), EarthAbility.getTempAirLocations().keySet())
                || !Collections.disjoint(earthQueue.keySet(), RevertChecker.earthQueue().keySet()) || !Collections.disjoint(airQueue.keySet(), RevertChecker.airQueue().keySet())
                || !Collections.disjoint(walls.keySet(), RaiseEarth.walls().keySet()) || !Collections.disjoint(columns.keySet(), RaiseEarth.columns().keySet())
                || !Collections.disjoint(active.keySet(), RaiseEarth.activeBlocks().keySet()) || !Collections.disjoint(prevented, EarthAbility.getPreventEarthbendingBlocks())
                || !Collections.disjoint(physics, EarthAbility.getPreventPhysicsBlocks())) throw new IllegalStateException("Overlapping live earth restoration");
    }
    public void detach() {
        moved.keySet().forEach(EarthAbility.getMovedEarth()::remove); air.keySet().forEach(EarthAbility.getTempAirLocations()::remove);
        earthQueue.keySet().forEach(RevertChecker.earthQueue()::remove); airQueue.keySet().forEach(RevertChecker.airQueue()::remove);
        walls.keySet().forEach(RaiseEarth.walls()::remove); columns.keySet().forEach(RaiseEarth.columns()::remove); active.keySet().forEach(RaiseEarth.activeBlocks()::remove);
        EarthAbility.getPreventEarthbendingBlocks().removeAll(prevented); EarthAbility.getPreventPhysicsBlocks().removeAll(physics);
    }
    public void merge() {
        EarthAbility.getMovedEarth().putAll(moved); EarthAbility.getTempAirLocations().putAll(air);
        RevertChecker.earthQueue().putAll(earthQueue); RevertChecker.airQueue().putAll(airQueue);
        RaiseEarth.walls().putAll(walls); RaiseEarth.columns().putAll(columns); RaiseEarth.activeBlocks().putAll(active);
        EarthAbility.getPreventEarthbendingBlocks().addAll(prevented); EarthAbility.getPreventPhysicsBlocks().addAll(physics);
    }
}

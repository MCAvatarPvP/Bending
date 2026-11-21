package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.world.level.border.BorderChangeListener;
import net.minecraft.world.level.border.BorderStatus;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Objects;

/** Owned native border. Replay never consults Paper's real-server-tick deduplication or a live world. */
public final class PaperRollbackBorder extends WorldBorder implements RollbackStateCell<PaperRollbackBorder.Snapshot> {
    public static final class Snapshot {
        private final PaperRollbackBorder owner;
        private final RollbackBorderData data;
        private final Long lastTick;
        private Snapshot(PaperRollbackBorder owner) { this.owner = owner; data = owner.data(); lastTick = owner.lastTick; }
    }
    private final Thread thread = Thread.currentThread();
    private Long lastTick;

    public PaperRollbackBorder(RollbackBorderData seed) {
        requireSetup(); Access.prepare(); super.applyInitialSettings(0); apply(Objects.requireNonNull(seed));
    }
    public static RollbackBorderData capture(WorldBorder source) { requireSetup(); Access.prepare(); return stateOf(source); }
    public RollbackBorderData data() { check(); return stateOf(this); }
    /** One border step per logical world tick, including several replay ticks in one real server tick. */
    public void advanceTick(long tick) {
        check();
        if (lastTick != null) { if (tick < lastTick) throw new IllegalStateException("Border tick moved backwards without rewind"); if (tick == lastTick) return; }
        Access.step(this); lastTick = tick;
    }
    @Override public void tick() { throw new IllegalStateException("Advance private border with its logical world tick"); }
    @Override public void addListener(BorderChangeListener listener) { throw new UnsupportedOperationException("Private border cannot publish live listeners"); }
    @Override public void removeListener(BorderChangeListener listener) { throw new UnsupportedOperationException("Private border has no live listeners"); }
    @Override public void applyInitialSettings(long tick) { throw new UnsupportedOperationException("Private border is already initialized"); }
    @Override public double getCenterX() { check(); return super.getCenterX(); }
    @Override public double getCenterZ() { check(); return super.getCenterZ(); }
    @Override public void setCenter(double x, double z) { check(); super.setCenter(x, z); }
    @Override public double getSize() { check(); return super.getSize(); }
    @Override public void setSize(double size) { check(); super.setSize(size); }
    @Override public void lerpSizeBetween(double from, double to, long duration, long begin) { check(); super.lerpSizeBetween(from, to, duration, begin); }
    @Override public long getLerpTime() { check(); return super.getLerpTime(); }
    @Override public double getLerpTarget() { check(); return super.getLerpTarget(); }
    @Override public double getLerpSpeed() { check(); return super.getLerpSpeed(); }
    @Override public BorderStatus getStatus() { check(); return super.getStatus(); }
    @Override public VoxelShape getCollisionShape() { check(); return super.getCollisionShape(); }
    @Override public double getMinX(float tick) { check(); return super.getMinX(tick); }
    @Override public double getMaxX(float tick) { check(); return super.getMaxX(tick); }
    @Override public double getMinZ(float tick) { check(); return super.getMinZ(tick); }
    @Override public double getMaxZ(float tick) { check(); return super.getMaxZ(tick); }
    @Override public int getAbsoluteMaxSize() { check(); return super.getAbsoluteMaxSize(); }
    @Override public void setAbsoluteMaxSize(int size) { check(); super.setAbsoluteMaxSize(size); }
    @Override public double getSafeZone() { check(); return super.getSafeZone(); }
    @Override public void setSafeZone(double value) { check(); super.setSafeZone(value); }
    @Override public double getDamagePerBlock() { check(); return super.getDamagePerBlock(); }
    @Override public void setDamagePerBlock(double value) { check(); super.setDamagePerBlock(value); }
    @Override public int getWarningTime() { check(); return super.getWarningTime(); }
    @Override public void setWarningTime(int value) { check(); super.setWarningTime(value); }
    @Override public int getWarningBlocks() { check(); return super.getWarningBlocks(); }
    @Override public void setWarningBlocks(int value) { check(); super.setWarningBlocks(value); }
    @Override public Snapshot captureRollbackState() { check(); return new Snapshot(this); }
    @Override public void restoreRollbackState(Snapshot snapshot) {
        check(); if (snapshot.owner != this) throw new IllegalArgumentException("Foreign border checkpoint"); apply(snapshot.data); lastTick = snapshot.lastTick;
    }
    private void apply(RollbackBorderData seed) {
        setCenter(seed.centerX(), seed.centerZ()); setAbsoluteMaxSize(seed.absoluteMaxSize());
        setDamagePerBlock(seed.damagePerBlock()); setSafeZone(seed.safeZone()); setWarningBlocks(seed.warningBlocks()); setWarningTime(seed.warningTime());
        var move = seed.transition();
        if (move == null) setSize(seed.size());
        else { lerpSizeBetween(move.from(), move.to(), move.duration(), move.begin()); Access.restoreProgress(this, move.remaining(), seed.size(), move.previousSize()); }
    }
    private static RollbackBorderData stateOf(WorldBorder source) {
        var move = source.getStatus() == BorderStatus.STATIONARY ? null : Access.transition(source);
        return new RollbackBorderData(source.getCenterX(), source.getCenterZ(), source.getAbsoluteMaxSize(), source.getDamagePerBlock(), source.getSafeZone(),
                source.getWarningBlocks(), source.getWarningTime(), source.getSize(), move);
    }
    private void check() {
        // The native superclass builds its initial shape through virtual center getters before our fields initialize.
        if (thread != null && thread != Thread.currentThread()) throw new IllegalStateException("Private border crossed threads");
        if (world != null) throw new IllegalStateException("Private border attached to a live world");
    }
    private static void requireSetup() { if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Prepare native border before replay"); }

    /** Fixed private-member bindings unavailable through paperweight's public API. No lookup occurs in capture/replay. */
    private static final class Access {
        private static final MethodHandle EXTENT, SET_EXTENT, UPDATE, FROM, BEGIN, END, PREVIOUS, SET_PREVIOUS, SET_SIZE, SET_REMAINING;
        static {
            try {
                var extent = Class.forName("net.minecraft.world.level.border.WorldBorder$BorderExtent", false, WorldBorder.class.getClassLoader());
                var moving = Class.forName("net.minecraft.world.level.border.WorldBorder$MovingBorderExtent", false, WorldBorder.class.getClassLoader());
                var borderLookup = MethodHandles.privateLookupIn(WorldBorder.class, MethodHandles.lookup());
                var extentLookup = MethodHandles.privateLookupIn(extent, MethodHandles.lookup()); var lookup = MethodHandles.privateLookupIn(moving, MethodHandles.lookup());
                EXTENT = borderLookup.findGetter(WorldBorder.class, "extent", extent).asType(MethodType.methodType(Object.class, WorldBorder.class));
                SET_EXTENT = borderLookup.findSetter(WorldBorder.class, "extent", extent).asType(MethodType.methodType(void.class, WorldBorder.class, Object.class));
                UPDATE = extentLookup.findVirtual(extent, "update", MethodType.methodType(extent)).asType(MethodType.methodType(Object.class, Object.class));
                FROM = lookup.findGetter(moving, "from", double.class).asType(MethodType.methodType(double.class, Object.class));
                BEGIN = lookup.findGetter(moving, "lerpBegin", long.class).asType(MethodType.methodType(long.class, Object.class));
                END = lookup.findGetter(moving, "lerpEnd", long.class).asType(MethodType.methodType(long.class, Object.class));
                PREVIOUS = lookup.findGetter(moving, "previousSize", double.class).asType(MethodType.methodType(double.class, Object.class));
                SET_PREVIOUS = lookup.findSetter(moving, "previousSize", double.class).asType(MethodType.methodType(void.class, Object.class, double.class));
                SET_SIZE = lookup.findSetter(moving, "size", double.class).asType(MethodType.methodType(void.class, Object.class, double.class));
                SET_REMAINING = lookup.findSetter(moving, "lerpProgress", long.class).asType(MethodType.methodType(void.class, Object.class, long.class));
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
        static void prepare() { }
        static RollbackBorderData.Transition transition(WorldBorder source) {
            try {
                Object extent = EXTENT.invokeExact(source); long begin = (long) BEGIN.invokeExact(extent), end = (long) END.invokeExact(extent);
                return new RollbackBorderData.Transition((double) FROM.invokeExact(extent), source.getLerpTarget(), end - begin, begin, source.getLerpTime(), (double) PREVIOUS.invokeExact(extent));
            } catch (Throwable failure) { throw failed(failure); }
        }
        static void restoreProgress(WorldBorder target, long remaining, double size, double previous) {
            try { Object extent = EXTENT.invokeExact(target); SET_REMAINING.invokeExact(extent, remaining); SET_SIZE.invokeExact(extent, size); SET_PREVIOUS.invokeExact(extent, previous); }
            catch (Throwable failure) { throw failed(failure); }
        }
        static void step(WorldBorder border) {
            try { Object extent = EXTENT.invokeExact(border); Object updated = UPDATE.invokeExact(extent); SET_EXTENT.invokeExact(border, updated); }
            catch (Throwable failure) { throw failed(failure); }
        }
        private static RuntimeException failed(Throwable failure) {
            if (failure instanceof Error error) throw error; if (failure instanceof RuntimeException runtime) return runtime;
            return new IllegalStateException("Native border access failed", failure);
        }
    }
}

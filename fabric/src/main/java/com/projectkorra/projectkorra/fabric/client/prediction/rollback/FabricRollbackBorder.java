package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.border.WorldBorderListener;
import net.minecraft.world.border.WorldBorderStage;
import java.util.Objects;

/** The client's private native border, restored from the exact Paper curve rather than restarting its remaining resize. */
public final class FabricRollbackBorder extends WorldBorder implements RollbackStateCell<FabricRollbackBorder.Snapshot> {
    public static final class Snapshot {
        private final FabricRollbackBorder owner;
        private final RollbackBorderData data;
        private final Long lastTick;
        private Snapshot(FabricRollbackBorder owner) { this.owner = owner; data = owner.data(); lastTick = owner.lastTick; }
    }
    private final Thread thread = Thread.currentThread();
    private Long lastTick;
    public FabricRollbackBorder(RollbackBorderData seed) {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Prepare native border before replay");
        super.ensureInitialized(0); apply(Objects.requireNonNull(seed));
    }
    public RollbackBorderData data() {
        check(); var transition = area instanceof WorldBorder.MovingArea move
                ? new RollbackBorderData.Transition(move.oldSize, getSizeLerpTarget(), move.timeEnd - move.timeStart, move.timeStart, getSizeLerpTime(), move.lastSize) : null;
        return new RollbackBorderData(getCenterX(), getCenterZ(), getMaxRadius(), getDamagePerBlock(), getSafeZone(), getWarningBlocks(), getWarningTime(), getSize(), transition);
    }
    public void advanceTick(long tick) {
        check();
        if (lastTick != null) { if (tick < lastTick) throw new IllegalStateException("Border tick moved backwards without rewind"); if (tick == lastTick) return; }
        super.tick(); lastTick = tick;
    }
    @Override public void tick() { throw new IllegalStateException("Advance private border with its logical world tick"); }
    @Override public void addListener(WorldBorderListener listener) { throw new UnsupportedOperationException("Private border cannot publish live listeners"); }
    @Override public void removeListener(WorldBorderListener listener) { throw new UnsupportedOperationException("Private border has no live listeners"); }
    @Override public void ensureInitialized(long tick) { throw new UnsupportedOperationException("Private border is already initialized"); }
    @Override public double getCenterX() { check(); return super.getCenterX(); }
    @Override public double getCenterZ() { check(); return super.getCenterZ(); }
    @Override public void setCenter(double x, double z) { check(); super.setCenter(x, z); }
    @Override public double getSize() { check(); return super.getSize(); }
    @Override public void setSize(double size) { check(); super.setSize(size); }
    @Override public void interpolateSize(double from, double to, long duration, long begin) { check(); super.interpolateSize(from, to, duration, begin); }
    @Override public long getSizeLerpTime() { check(); return super.getSizeLerpTime(); }
    @Override public double getSizeLerpTarget() { check(); return super.getSizeLerpTarget(); }
    @Override public double getShrinkingSpeed() { check(); return super.getShrinkingSpeed(); }
    @Override public WorldBorderStage getStage() { check(); return super.getStage(); }
    @Override public VoxelShape asVoxelShape() { check(); return super.asVoxelShape(); }
    @Override public double getBoundWest(float tick) { check(); return super.getBoundWest(tick); }
    @Override public double getBoundEast(float tick) { check(); return super.getBoundEast(tick); }
    @Override public double getBoundNorth(float tick) { check(); return super.getBoundNorth(tick); }
    @Override public double getBoundSouth(float tick) { check(); return super.getBoundSouth(tick); }
    @Override public int getMaxRadius() { check(); return super.getMaxRadius(); }
    @Override public void setMaxRadius(int size) { check(); super.setMaxRadius(size); }
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
        setCenter(seed.centerX(), seed.centerZ()); setMaxRadius(seed.absoluteMaxSize());
        setDamagePerBlock(seed.damagePerBlock()); setSafeZone(seed.safeZone()); setWarningBlocks(seed.warningBlocks()); setWarningTime(seed.warningTime());
        var transition = seed.transition();
        if (transition == null) setSize(seed.size());
        else {
            interpolateSize(transition.from(), transition.to(), transition.duration(), transition.begin());
            var move = (WorldBorder.MovingArea) area;
            move.remainingTimeDuration = transition.remaining(); move.currentSize = seed.size(); move.lastSize = transition.previousSize();
        }
    }
    private void check() {
        // Native construction computes its first shape through virtual center getters.
        if (thread != null && thread != Thread.currentThread()) throw new IllegalStateException("Private border crossed threads");
    }
}

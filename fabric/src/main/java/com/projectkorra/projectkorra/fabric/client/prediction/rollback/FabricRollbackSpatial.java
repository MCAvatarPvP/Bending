package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.*;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.scoreboard.Scoreboard;
import java.util.List;
import java.util.Objects;

/** Owned spatial services for the private world; all mutable children participate in replay. */
public final class FabricRollbackSpatial implements FabricRollbackWorldServices.Spatial<Void> {
    private final Thread thread = Thread.currentThread();
    private final RollbackWorld logical;
    private final FabricRollbackLighting lighting;
    private final FabricRollbackEnvironment environment;
    private final FabricRollbackBorder border;
    private final Scoreboard scoreboard;

    public FabricRollbackSpatial(RollbackWorld logical, DynamicRegistryManager.Immutable registries,
            RollbackEnvironmentData environment, RollbackBorderData border, FabricRollbackLighting lighting, Scoreboard scoreboard) {
        if (RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Prepare private spatial services before replay");
        this.logical = Objects.requireNonNull(logical);
        this.scoreboard = Objects.requireNonNull(scoreboard, "private native team queries");
        this.lighting = Objects.requireNonNull(lighting);
        var bounds = lighting.bounds();
        if (!bounds.equals(logical.terrain().bounds()) || bounds.minY() != logical.getMinHeight()
                || bounds.maxY() != logical.getMaxHeight())
            throw new IllegalArgumentException("Private lighting must cover the captured world height and terrain");
        this.environment = new FabricRollbackEnvironment(logical, registries, environment);
        this.border = new FabricRollbackBorder(Objects.requireNonNull(border));
    }
    @Override public void advanceTick(long tick) { check(); border.advanceTick(tick); }
    @Override public FabricRollbackBorder border() { check(); return border; }
    @Override public Scoreboard scoreboard() { check(); return scoreboard; }
    @Override public FabricRollbackEnvironment environmentAttributes() { check(); return environment; }
    @Override public boolean skyVisible(RollbackBlockStore terrain, BlockPos position) {
        check();
        if (terrain != logical.terrain()) throw new IllegalArgumentException("Foreign terrain in private sky query");
        return lighting.sky(new RollbackBlockStore.Position(position.getX(), position.getY(), position.getZ())) == 15;
    }
    @Override public Void captureRollbackState() { check(); return null; }
    @Override public void restoreRollbackState(Void ignored) { check(); }
    @Override public List<?> rollbackReferences() { check(); return List.of(logical, lighting, environment, border, scoreboard); }
    private void check() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Private spatial services crossed threads");
    }
}

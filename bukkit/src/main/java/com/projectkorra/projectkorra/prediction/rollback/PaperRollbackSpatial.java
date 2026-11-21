package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import java.util.List;
import java.util.Objects;

/** Owned spatial services for the private world; all mutable children participate in replay. */
public final class PaperRollbackSpatial implements PaperRollbackWorldServices.Spatial<Void> {
    private final Thread thread = Thread.currentThread();
    private final RollbackWorld logical;
    private final PaperRollbackLighting lighting;
    private final PaperRollbackEnvironment environment;
    private final PaperRollbackBorder border;

    public PaperRollbackSpatial(RollbackWorld logical, RegistryAccess.Frozen registries,
            RollbackEnvironmentData environment, RollbackBorderData border, PaperRollbackLighting lighting) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active())
            throw new IllegalStateException("Prepare private spatial services before replay");
        this.logical = Objects.requireNonNull(logical);
        this.lighting = Objects.requireNonNull(lighting);
        var bounds = lighting.bounds();
        if (!bounds.equals(logical.terrain().bounds()) || bounds.minY() != logical.getMinHeight()
                || bounds.maxY() != logical.getMaxHeight())
            throw new IllegalArgumentException("Private lighting must cover the captured world height and terrain");
        this.environment = new PaperRollbackEnvironment(logical, registries, environment);
        this.border = new PaperRollbackBorder(Objects.requireNonNull(border));
    }
    @Override public void advanceTick(long tick) { check(); border.advanceTick(tick); }
    @Override public PaperRollbackBorder border() { check(); return border; }
    @Override public PaperRollbackEnvironment environmentAttributes() { check(); return environment; }
    @Override public boolean skyVisible(RollbackBlockStore terrain, BlockPos position) {
        check();
        if (terrain != logical.terrain()) throw new IllegalArgumentException("Foreign terrain in private sky query");
        return lighting.sky(new RollbackBlockStore.Position(position.getX(), position.getY(), position.getZ())) == 15;
    }
    @Override public Void captureRollbackState() { check(); return null; }
    @Override public void restoreRollbackState(Void ignored) { check(); }
    @Override public List<?> rollbackReferences() { check(); return List.of(logical, lighting, environment, border); }
    private void check() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Private spatial services crossed threads");
    }
}

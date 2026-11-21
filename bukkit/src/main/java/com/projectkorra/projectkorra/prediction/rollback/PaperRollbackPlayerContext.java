package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.*;
import com.mojang.authlib.GameProfile;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Native player shape queries over a private replica, refreshed from logical state.
 * Construct once outside replay. The replica is never spawned/ticked and owns no live
 * world or connection. This is a collision context, not a native movement simulation.
 */
public final class PaperRollbackPlayerContext implements RollbackStateCell<Void> {
    private static final class QueryPlayer extends Player {
        private GameType mode = GameType.SURVIVAL;
        QueryPlayer(Level world, GameProfile profile) { super(world, profile); }
        @Override public GameType gameMode() { return mode; }
    }

    private final Thread thread = Thread.currentThread();
    private final RollbackPlayer owner;
    private final RollbackNativeItems<ItemStack> items;
    private final QueryPlayer replica;
    private boolean querying;

    public PaperRollbackPlayerContext(RollbackPlayer owner, RollbackNativeItems<ItemStack> items) {
        this.owner = Objects.requireNonNull(owner, "player");
        this.items = Objects.requireNonNull(items, "items");
        items.requireOwnerThread();
        if (RollbackClock.active()) throw new IllegalStateException("Create native query replicas before replay");
        if (!owner.state().inventory().layout().equals(PaperRollbackInventory.layout())) {
            throw new IllegalArgumentException("Player inventory does not use the native layout");
        }
        Level world = RollbackNativeQueryShell.create(Level.class).constant(Level::isClientSide, false).instance();
        // ActivationRange reads configuration during native Entity construction.
        PaperRollbackPrivateAccess.initializeConfig(world);
        replica = new QueryPlayer(world, new GameProfile(owner.getUniqueId(), owner.getName()));
        replica.setId(owner.getEntityId());
    }

    @SuppressWarnings("WrapperReferenceEquality") // Logical worlds have session identity, not value equality.
    public List<Object> movementColliders(PaperRollbackGeometry geometry, RollbackBlockStore terrain, RollbackBlockStore.Box bounds) {
        requireIdle();
        if (terrain.world() != owner.getWorld()) throw new IllegalArgumentException("Player and terrain belong to different worlds");
        return query(context -> geometry.movementColliders(terrain, bounds, context));
    }

    /** Native values are ephemeral: never keep a context/entity beyond this callback. */
    <R> R query(Function<Object, R> operation) {
        requireIdle();
        querying = true;
        try {
            synchronize();
            return operation.apply(CollisionContext.of(replica));
        } finally { querying = false; }
    }

    // The replica is a derived cache. Every query refreshes it, including after restore.
    @Override public Void captureRollbackState() { requireIdle(); return null; }
    @Override public void restoreRollbackState(Void ignored) { requireIdle(); }
    @Override public List<?> rollbackReferences() { requireIdle(); return List.of(owner); }

    private void requireIdle() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Native player query used from another thread");
        if (querying) throw new IllegalStateException("Reentrant native player query or snapshot");
    }

    private void synchronize() {
        var body = owner.body();
        if (!body.valid() || body.dead()) throw new IllegalStateException("Querying an inactive logical player");
        var state = body.kinematics();
        var pose = state.pose();
        replica.setPos(pose.x(), pose.y(), pose.z());
        replica.setYRot(pose.yaw());
        replica.setXRot(pose.pitch());
        var bounds = body.bounds();
        replica.setBoundingBox(new AABB(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(),
                bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ()));
        replica.setShiftKeyDown(owner.isSneaking());
        replica.fallDistance = state.fallDistance();
        replica.mode = GameType.valueOf(owner.state().profile().gameMode());
        var inventory = replica.getInventory();
        var logical = owner.state().inventory();
        for (int slot = 0; slot < logical.getSize(); slot++) {
            var item = logical.getItem(slot);
            inventory.setItem(slot, item == null ? ItemStack.EMPTY : items.nativeCopy(item));
        }
        inventory.setSelectedSlot(logical.getHeldItemSlot());
    }

}

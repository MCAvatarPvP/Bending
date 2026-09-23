package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.mojang.authlib.GameProfile;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackStateCell;
import com.projectkorra.projectkorra.prediction.rollback.world.*;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Native shape context for a private, never spawned/ticked player query replica. */
public final class FabricRollbackPlayerContext implements RollbackStateCell<Void> {
    private final Thread thread = Thread.currentThread();
    private final RollbackPlayer owner;
    private final RollbackNativeItems<ItemStack> items;
    private final Replica replica;
    private boolean querying;

    public FabricRollbackPlayerContext(RollbackPlayer owner, RollbackNativeItems<ItemStack> items) {
        this.owner = Objects.requireNonNull(owner, "player");
        this.items = Objects.requireNonNull(items, "items");
        items.requireOwnerThread();
        if (RollbackClock.active()) throw new IllegalStateException("Create native query replicas before replay");
        if (!owner.state().inventory().layout().equals(FabricRollbackInventory.layout())) {
            throw new IllegalArgumentException("Player inventory does not use the native layout");
        }
        World world = RollbackNativeQueryShell.create(World.class).constant(World::isClient, false).instance();
        replica = new Replica(world, new GameProfile(owner.getUniqueId(), owner.getName()));
        replica.setId(owner.getEntityId());
    }

    @SuppressWarnings("WrapperReferenceEquality") // Logical worlds have session identity, not value equality.
    public List<VoxelShape> movementColliders(FabricRollbackGeometry geometry, RollbackBlockStore terrain, RollbackBlockStore.Box bounds) {
        requireIdle();
        if (terrain.world() != owner.getWorld()) throw new IllegalArgumentException("Player and terrain belong to different worlds");
        return query(context -> geometry.movementColliders(terrain, bounds, context));
    }

    /** Native values are ephemeral: never keep a context/entity beyond this callback. */
    <R> R query(Function<ShapeContext, R> operation) {
        requireIdle();
        querying = true;
        try {
            synchronize();
            return operation.apply(ShapeContext.of(replica));
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
        replica.setPosition(pose.x(), pose.y(), pose.z());
        replica.setYaw(pose.yaw());
        replica.setPitch(pose.pitch());
        var bounds = body.bounds();
        replica.setBoundingBox(new Box(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX(), bounds.getMaxY(), bounds.getMaxZ()));
        replica.setSneaking(owner.isSneaking());
        replica.fallDistance = state.fallDistance();
        replica.mode = GameMode.valueOf(owner.state().profile().gameMode());
        var inventory = replica.getInventory();
        var logical = owner.state().inventory();
        for (int slot = 0; slot < logical.getSize(); slot++) {
            var item = logical.getItem(slot);
            inventory.setStack(slot, item == null ? ItemStack.EMPTY : items.nativeCopy(item));
        }
        inventory.setSelectedSlot(logical.getHeldItemSlot());
    }

    private static final class Replica extends PlayerEntity {
        private GameMode mode = GameMode.SURVIVAL;
        private Replica(World world, GameProfile profile) { super(world, profile); }
        @Override public GameMode getGameMode() { return mode; }
    }
}

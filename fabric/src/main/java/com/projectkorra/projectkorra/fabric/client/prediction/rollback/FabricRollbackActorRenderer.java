package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.google.common.collect.Iterables;
import com.projectkorra.projectkorra.fabric.mixin.client.DataTrackerRollbackAccess;
import net.minecraft.block.Block;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.*;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import java.util.*;
import java.util.function.Supplier;

/** Client-world models of private combat actors. Only world rendering can enumerate these copies. */
final class FabricRollbackActorRenderer {
    private final ClientWorld world;
    private final Supplier<List<Entity>> active;
    private Map<UUID, Entity> models = Map.of();
    private long lastTick = -1;

    FabricRollbackActorRenderer(ClientWorld world, Supplier<List<Entity>> active) {
        this.world = world;
        this.active = active;
    }

    void publish(long tick) {
        List<Entity> actors = active.get();
        if (actors.isEmpty()) {
            models = Map.of();
        } else {
            var next = new LinkedHashMap<UUID, Entity>();
            for (Entity source : actors) {
                // During handoff the materialized live actor already has its own model.
                if (source.isRemoved() || world.getEntity(source.getUuid()) != null) continue;
                Entity model = models.get(source.getUuid());
                if (model == null || model.getType() != source.getType()) model = create(source);
                copy(source, model);
                // Vanilla draws cloud particles in its client tick. A correction of the same tick
                // must not emit a second burst. The copy is never installed in the live entity index.
                if (model instanceof AreaEffectCloudEntity cloud && tick > lastTick) cloud.tick();
                next.put(source.getUuid(), model);
            }
            models = next;
        }
        lastTick = tick;
    }

    Entity model(UUID id) { return models.get(id); }
    boolean owns(Entity model) { return models.get(model.getUuid()) == model; }

    Iterable<Entity> append(Iterable<Entity> ordinary) {
        return models.isEmpty() ? ordinary : Iterables.concat(ordinary, models.values());
    }

    private Entity create(Entity source) {
        Entity model = Objects.requireNonNull(source.getType().create(world, SpawnReason.LOAD));
        int extra = 0;
        if (source instanceof FallingBlockEntity block) extra = Block.getRawIdFromState(block.getBlockState());
        else if (source instanceof ProjectileEntity projectile && projectile.getOwner() != null) extra = projectile.getOwner().getId();
        model.onSpawnPacket(new EntitySpawnS2CPacket(source.getId(), source.getUuid(), source.getX(), source.getY(),
                source.getZ(), source.getPitch(), source.getYaw(), source.getType(), extra, source.getVelocity(), source.getHeadYaw()));
        return model;
    }

    private static void copy(Entity source, Entity model) {
        model.refreshPositionAndAngles(source.getX(), source.getY(), source.getZ(), source.getYaw(), source.getPitch());
        model.lastX = source.lastX;
        model.lastY = source.lastY;
        model.lastZ = source.lastZ;
        model.lastRenderX = source.lastRenderX;
        model.lastRenderY = source.lastRenderY;
        model.lastRenderZ = source.lastRenderZ;
        model.lastPitch = source.lastPitch;
        model.lastYaw = source.lastYaw;
        model.age = source.age;
        model.setVelocity(source.getVelocity());
        model.setOnGround(source.isOnGround());
        for (var entry : ((DataTrackerRollbackAccess)source.getDataTracker()).rollback$entries()) copy(entry, model.getDataTracker());
        if (source instanceof PersistentProjectileEntity arrow && model instanceof PersistentProjectileEntity copy) copy.shake = arrow.shake;
        if (source instanceof LivingEntity living && model instanceof LivingEntity copy) {
            copy.setHeadYaw(living.getHeadYaw());
            copy.setBodyYaw(living.getBodyYaw());
            for (var slot : EquipmentSlot.values()) {
                var stack = living.getEquippedStack(slot);
                if (!ItemStack.areEqual(stack, copy.getEquippedStack(slot))) copy.equipStack(slot, stack.copy());
            }
        }
    }
    private static <T> void copy(DataTracker.Entry<T> entry, DataTracker tracker) {
        var key = entry.getData();
        var value = entry.get();
        var previous = tracker.get(key);
        boolean equal = value instanceof ItemStack stack && previous instanceof ItemStack before
                ? ItemStack.areEqual(before, stack) : Objects.equals(previous, value);
        if (!equal) tracker.set(key, key.dataType().copy(value));
    }
}

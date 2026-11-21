package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackNativeMethods;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.bukkit.craftbukkit.event.CraftEventFactory;
import org.bukkit.event.entity.EntityRemoveEvent;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Set;

/** Compiled stock Paper item callbacks use only the scene's own Bukkit event dispatcher. */
final class PaperRollbackItemLifecycle {
    record Collection(java.util.UUID item, java.util.UUID collector, int count) implements PaperRollbackCombatAccess.Output {}
    private final PaperRollbackWorldAccess world;
    private final MethodHandle tick, touch;
    PaperRollbackItemLifecycle(PaperRollbackWorldAccess world) {
        this.world = world;
        var methods = new RollbackNativeMethods();
        world.bindEvents(methods);
        try {
            var type = ItemEntity.class;
            var tickMethod = type.getDeclaredMethod("tick");
            var touchMethod = type.getDeclaredMethod("playerTouch", Player.class);
            for (var m : type.getDeclaredMethods()) {
                if (Set.of("tick", "mergeWithNeighbours", "tryToMerge", "merge", "playerTouch").contains(m.getName()))
                    methods.copy(m);
                else if (m.getName().startsWith("lambda$mergeWithNeighbours$")) methods.copyLambda(m);
            }
            for (var m : CraftEventFactory.class.getDeclaredMethods()) if (Set.of("callItemDespawnEvent", "callItemMergeEvent").contains(m.getName())) methods.copy(m);
            var lookup = MethodHandles.lookup();
            methods.replace(Entity.class.getMethod("discard", EntityRemoveEvent.Cause.class),
                    lookup.findVirtual(PaperRollbackItemLifecycle.class, "retire", MethodType.methodType(void.class, Entity.class, EntityRemoveEvent.Cause.class)).bindTo(this));
            methods.replace(Player.class.getMethod("awardStat", net.minecraft.stats.Stat.class, int.class),
                    lookup.findVirtual(PaperRollbackItemLifecycle.class, "award",
                            MethodType.methodType(void.class, Player.class, net.minecraft.stats.Stat.class, int.class)).bindTo(this));
            methods.replace(LivingEntity.class.getMethod("take", Entity.class, int.class),
                    lookup.findVirtual(PaperRollbackItemLifecycle.class, "collect", MethodType.methodType(void.class, LivingEntity.class, Entity.class, int.class)).bindTo(this));
            methods.replace(org.bukkit.entity.LivingEntity.class.getMethod("getCanPickupItems"),
                    lookup.findVirtual(PaperRollbackItemLifecycle.class, "canPickup", MethodType.methodType(boolean.class, org.bukkit.entity.LivingEntity.class)).bindTo(this));
            var sites = world.prepareMethods(methods);
            tick = sites.get(tickMethod); touch = sites.get(touchMethod);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Native item callbacks changed", error); }
    }
    private void retire(Entity item, EntityRemoveEvent.Cause cause) {
        if (!(item instanceof PaperRollbackItemEntity) || !world.ownsEntity(item)) throw new IllegalArgumentException("Foreign removed item");
        world.event(new EntityRemoveEvent(item.getBukkitEntity(), cause));
        PaperRollbackPrivateAccess.retirePrivateActor(item);
    }
    private void award(Player player, net.minecraft.stats.Stat<?> statistic, int count) {
        world.statistic(player, statistic, count, false);
    }
    private void collect(LivingEntity player, Entity item, int count) {
        if (!world.ownsPlayer(player) || !world.ownsEntity(item)) throw new IllegalArgumentException("Foreign private item pickup");
        world.output(new Collection(item.getUUID(), player.getUUID(), count));
    }
    private boolean canPickup(org.bukkit.entity.LivingEntity wrapper) {
        if (!(wrapper instanceof org.bukkit.craftbukkit.entity.CraftPlayer p) || !world.ownsPlayer(p.getHandle()))
            throw new IllegalArgumentException("Foreign private pickup player");
        return p.getHandle().bukkitPickUpLoot;
    }
    void tick(ItemEntity item) {
        if (!world.ownsEntity(item)) throw new IllegalArgumentException("Foreign private item tick");
        try { tick.invokeExact(item); }
        catch (RuntimeException | Error error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Private native item tick failed", error); }
    }
    void touch(ItemEntity item, Player player) {
        if (!world.ownsPlayer(player) || !world.ownsEntity(item)) throw new IllegalArgumentException("Foreign private item contact");
        try { touch.invokeExact(item, player); }
        catch (RuntimeException | Error error) { throw error; }
        catch (Throwable error) { throw new IllegalStateException("Private native item pickup failed", error); }
    }
}

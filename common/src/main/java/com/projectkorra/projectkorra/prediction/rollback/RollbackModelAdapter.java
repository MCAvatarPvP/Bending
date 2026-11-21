package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.platform.model.*;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import java.util.*;

/** Model API over the same owned entities and terrain used by ability-facing views. */
final class RollbackModelAdapter implements PKAdapter, RollbackStateCell<Void> {
    private final RollbackWorld arena;
    RollbackModelAdapter(RollbackWorld arena) { this.arena = Objects.requireNonNull(arena); }
    @Override public PKEntity entity(Object value) {
        if (!(value instanceof Entity body) || !(value instanceof RollbackEntityBody.View)
                || !owns(arena, body)) return null;
        if (body instanceof Player player) return new PlayerModel(player);
        if (body instanceof LivingEntity living) return new LivingModel(living);
        return new EntityModel(body);
    }
    private static boolean owns(RollbackWorld world, Entity entity) {
        if (entity.getWorld().handle() != world.handle()) return false;
        var registered = world.entities().get(entity.getUniqueId());
        return registered != null && registered.handle() == entity.handle();
    }
    @Override public PKPlayer player(Object value) { return entity(value) instanceof PKPlayer player ? player : null; }
    @Override public PKLivingEntity livingEntity(Object value) { return entity(value) instanceof PKLivingEntity living ? living : null; }
    @Override public PKWorld world(Object value) { return value == arena ? new WorldModel(arena) : null; }
    @Override public PKBlock block(Object value) {
        if (!(value instanceof Block block) || block.getWorld() != arena) return null;
        return new BlockModel(arena.getBlockAt(block.getX(), block.getY(), block.getZ()));
    }
    @Override public PKLocation location(Object value) {
        return value instanceof Location at && at.getWorld() == arena ? new LocationModel(at) : null;
    }
    @Override public Object unwrap(Object value) { return value instanceof PKHandle model ? model.handle() : value; }
    @Override public Void captureRollbackState() { return null; }
    @Override public void restoreRollbackState(Void saved) { }

    private interface EntityView extends PKEntity {
        Entity delegate();
        @Override default Object handle() { return delegate(); }
        @Override default UUID uuid() { return delegate().getUniqueId(); }
        @Override default PKWorld world() { return new WorldModel((RollbackWorld) delegate().getWorld()); }
        @Override default PKLocation location() { return new LocationModel(delegate().getLocation()); }
        @Override default PKVec3 velocity() { return vector(delegate().getVelocity()); }
        @Override default void velocity(PKVec3 v) { delegate().setVelocity(new Vector(v.x(), v.y(), v.z())); }
        @Override default boolean valid() { return delegate().isValid(); }
        @Override default void remove() { delegate().remove(); }
    }
    private interface LivingView extends EntityView, PKLivingEntity {
        @Override LivingEntity delegate();
        @Override default double health() { return delegate().getHealth(); }
        @Override default void damage(double amount, PKEntity source) {
            if (source != null && !(source instanceof EntityView))
                throw new IllegalArgumentException("Foreign model damage source");
            var world = (RollbackWorld) delegate().getWorld();
            world.applyDamage(delegate(), amount, source == null ? null : ((EntityView) source).delegate());
        }
    }
    private record EntityModel(Entity delegate) implements EntityView { }
    private record LivingModel(LivingEntity delegate) implements LivingView { }
    private record PlayerModel(Player delegate) implements LivingView, PKPlayer {
        @Override public UUID uuid() { return delegate.getUniqueId(); }
        @Override public String name() { return delegate.getName(); }
        @Override public void sendMessage(String text) { delegate.sendMessage(text); }
        @Override public boolean hasPermission(String permission) { return delegate.hasPermission(permission); }
        @Override public boolean sneaking() { return delegate.isSneaking(); }
        @Override public boolean sprinting() { return delegate.isSprinting(); }
        @Override public PKLocation eyeLocation() { return new LocationModel(delegate.getEyeLocation()); }
        @Override public PKBlock targetBlock(int range) {
            var value = delegate.getTargetBlockExact(range);
            return value == null ? null : new BlockModel(value);
        }
    }
    private record WorldModel(RollbackWorld handle) implements PKWorld {
        @Override public String name() { return handle.getName(); }
        @Override public PKBlock blockAt(int x, int y, int z) { return new BlockModel(handle.getBlockAt(x, y, z)); }
        private Location at(PKLocation location) {
            if (!(location instanceof LocationModel at) || at.handle.getWorld() != handle) throw new IllegalArgumentException("Foreign model location");
            return at.handle;
        }
        @Override public void playSound(PKLocation at, String sound, float volume, float pitch) { handle.playSound(at(at), sound, volume, pitch); }
        @Override public void spawnParticle(String particle, PKLocation at, int count, double x, double y, double z, double extra) {
            handle.spawnParticle(Particle.valueOf(particle), at(at), count, x, y, z, extra);
        }
    }
    private record BlockModel(Block handle) implements PKBlock {
        @Override public PKWorld world() { return new WorldModel((RollbackWorld) handle.getWorld()); }
        @Override public int x() { return handle.getX(); }
        @Override public int y() { return handle.getY(); }
        @Override public int z() { return handle.getZ(); }
        @Override public String materialKey() { return handle.getType().name().toLowerCase(Locale.ROOT); }
        @Override public boolean liquid() { return handle.isLiquid(); }
        @Override public boolean passable() { return handle.isPassable(); }
        @Override public PKLocation location() { return new LocationModel(handle.getLocation()); }
    }
    private record LocationModel(Location handle) implements PKLocation {
        @Override public PKWorld world() { return new WorldModel((RollbackWorld) handle.getWorld()); }
        @Override public double x() { return handle.getX(); }
        @Override public double y() { return handle.getY(); }
        @Override public double z() { return handle.getZ(); }
        @Override public float yaw() { return handle.getYaw(); }
        @Override public float pitch() { return handle.getPitch(); }
        @Override public PKVec3 direction() { return vector(handle.getDirection()); }
    }
    private static PKVec3 vector(Vector v) { return new PKVec3(v.getX(), v.getY(), v.getZ()); }
}

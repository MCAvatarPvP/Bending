package com.projectkorra.projectkorra.platform.mc;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.RayTraceResult;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class World {
    public String getName() {
        return "";
    }

    public long getTime() {
        return 0L;
    }

    public long getFullTime() {
        return getTime();
    }

    public Environment getEnvironment() {
        return Environment.NORMAL;
    }

    public int getMinHeight() {
        return -64;
    }

    public int getMaxHeight() {
        return 320;
    }

    public Object handle() {
        return this;
    }

    public void playSound(Location location, Sound sound, float volume, float pitch) {
    }

    public void playSound(Location location, String sound, float volume, float pitch) {
    }

    public void playSound(Entity entity, Sound sound, float volume, float pitch) {
    }

    public void playSound(Entity entity, Sound sound, SoundCategory category, float volume, float pitch) {
    }

    public Item dropItem(Location location, ItemStack item) {
        return new Item();
    }

    public Item dropItemNaturally(Location location, ItemStack item) {
        return new Item();
    }

    public Block getBlockAt(Location location) {
        return getBlockAt(location.getBlockX(), location.getBlockY(), location.getBlockZ());
    }

    public void playSound(Location location, Sound sound, SoundCategory category, float volume, float pitch) {
    }

    public void playEffect(Location location, Effect effect, int data) {
    }

    public void playEffect(Location location, Effect effect, Object data) {
    }

    public void playEffect(Location location, Effect effect, int data, int radius) {
    }

    public void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz, double extra) {
    }

    public void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz) {
    }

    public <T> void spawnParticle(Particle particle, Location location, int count, double ox, double oy, double oz, double extra, T data) {
    }

    public <T> T spawn(Location location, Class<T> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalArgumentException(type.getName(), ex);
        }
    }

    public <T> T spawn(Location location, Class<T> type, Consumer<T> consumer) {
        T value = spawn(location, type);
        consumer.accept(value);
        return value;
    }

    public RayTraceResult rayTraceBlocks(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable) {
        return null;
    }

    public RayTraceResult rayTrace(Location origin, Vector direction, double range, FluidCollisionMode mode, boolean ignorePassable, double raySize, Predicate<Entity> filter) {
        return null;
    }

    public Collection<Entity> getNearbyEntities(BoundingBox box, Predicate<Entity> filter) {
        return List.of();
    }

    public Collection<LivingEntity> getNearbyLivingEntities(Location location, double x, double y) {
        return getNearbyLivingEntities(location, x, y, x);
    }

    public Collection<LivingEntity> getNearbyLivingEntities(Location location, double x, double y, double z) {
        BoundingBox box = new BoundingBox(
                new Vector(location.getX() - x, location.getY() - y, location.getZ() - z),
                new Vector(location.getX() + x, location.getY() + y, location.getZ() + z));
        return getNearbyEntities(box, entity -> entity instanceof LivingEntity)
                .stream().map(entity -> (LivingEntity) entity).toList();
    }

    public Block getBlockAt(int x, int y, int z) {
        return new Block();
    }

    public Difficulty getDifficulty() {
        return Difficulty.PEACEFUL;
    }

    public Entity spawnEntity(Location location, EntityType type) {
        return type == EntityType.SLIME ? new Slime() : new Entity();
    }

    public FallingBlock spawnFallingBlock(Location location, BlockData data) {
        return new FallingBlock();
    }

    public Collection<Player> getPlayers() {
        return Platform.players().onlinePlayers();
    }

    public boolean createExplosion(double x, double y, double z, float power, boolean fire, boolean breakBlocks) {
        return true;
    }

    public boolean createExplosion(Location location, float power) {
        return createExplosion(location.getX(), location.getY(), location.getZ(), power, false, false);
    }

    public void strikeLightningEffect(Location location, boolean flash) {
    }

    public Arrow spawnArrow(Location location, Vector direction, float speed, float spread) {
        return new Arrow();
    }

    public Arrow spawnArrow(Location location, Vector direction, int speed, float spread) {
        return spawnArrow(location, direction, (float) speed, spread);
    }

    public boolean hasStorm() {
        return false;
    }

    public Block getHighestBlockAt(Location location) {
        return getBlockAt(location);
    }

    public double getTemperature(int x, int y, int z) {
        return 0.8;
    }

    public double getHumidity(int x, int y, int z) {
        return 0.4;
    }

    public List<Entity> getEntities() {
        return List.of();
    }

    public boolean isChunkLoaded(int x, int z) {
        return true;
    }

    public static class Environment {
        public static final Environment NORMAL = new Environment();
        public static final Environment NETHER = new Environment();
        public static final Environment THE_END = new Environment();
    }
}

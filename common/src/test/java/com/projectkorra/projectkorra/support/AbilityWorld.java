package com.projectkorra.projectkorra.support;

import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.PKServer;
import com.projectkorra.projectkorra.platform.PKMaterials;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.metadata.MetadataValue;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.*;
import java.lang.reflect.Proxy;
import java.lang.reflect.Field;
import java.io.File;
import java.util.function.Predicate;

/** In-memory blocks and player collision boxes for temporary terrain regression tests. */
public final class AbilityWorld extends World implements AutoCloseable {
    private final Map<String, TestBlock> blocks = new HashMap<>();
    private final List<Entity> entities = new ArrayList<>();
    private final Field platformField;
    private final Object previousPlatform;
    private final Config previousConfig;

    public AbilityWorld() {
        try {
            platformField = Platform.class.getDeclaredField("current");
            platformField.setAccessible(true);
            previousPlatform = platformField.get(null);
            previousConfig = ConfigManager.defaultConfig;
            var configConstructor = Config.class.getDeclaredConstructor(File.class, boolean.class);
            configConstructor.setAccessible(true);
            ConfigManager.defaultConfig = configConstructor.newInstance(
                    new File(System.getProperty("java.io.tmpdir"), UUID.randomUUID() + ".yml"), false);
            PKServer server = (PKServer) Proxy.newProxyInstance(PKServer.class.getClassLoader(),
                    new Class<?>[]{PKServer.class}, (proxy, method, args) -> {
                        if (method.getName().equals("minecraftVersion")) return "1.21.11";
                        throw new AssertionError(method);
                    });
            Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                    new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                        if (method.getName().equals("server")) return server;
                        if (method.getName().equals("materials")) return (PKMaterials) material ->
                                material == Material.STONE || material == Material.GRASS_BLOCK || material == Material.ICE;
                        throw new AssertionError(method);
                    }));
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    @Override public void close() throws IllegalAccessException {
        ConfigManager.defaultConfig = previousConfig;
        platformField.set(null, previousPlatform);
    }

    @Override public String getName() { return "ability-test"; }
    @Override public TestBlock getBlockAt(int x, int y, int z) {
        return blocks.computeIfAbsent(x + "," + y + "," + z, key -> new TestBlock(x, y, z));
    }
    @Override public Collection<Entity> getNearbyEntities(BoundingBox box, Predicate<Entity> filter) {
        return entities.stream().filter(filter).filter(entity -> entity.getBoundingBox().overlaps(box)).toList();
    }

    public TestPlayer player(double x, double y, double z) {
        TestPlayer player = new TestPlayer(new Location(this, x, y, z));
        entities.add(player);
        return player;
    }

    public final class TestBlock extends Block {
        private final Location location;
        private BlockData data = Material.AIR.createBlockData();
        private List<BoundingBox> collisionBoxes;
        private TestBlock(int x, int y, int z) { location = new Location(AbilityWorld.this, x, y, z); }
        @Override public World getWorld() { return AbilityWorld.this; }
        @Override public Location getLocation() { return location.clone(); }
        @Override public Material getType() { return data.getMaterial(); }
        @Override public BlockData getBlockData() { return data.clone(); }
        @Override public void setType(Material type) { data = type.createBlockData(); }
        @Override public void setBlockData(BlockData data) { this.data = data.clone(); }
        @Override public void setBlockData(BlockData data, boolean physics) { setBlockData(data); }
        public void setCollisionBoxes(BoundingBox... boxes) { collisionBoxes = List.of(boxes); }
        @Override public BoundingBox getBoundingBox() {
            return new BoundingBox(location.toVector(), location.clone().add(1, 1, 1).toVector());
        }
        @Override public List<BoundingBox> getCollisionBoxes() {
            return collisionBoxes == null ? super.getCollisionBoxes() : collisionBoxes;
        }
        @Override public Block getRelative(BlockFace face) {
            return switch (face) {
                case DOWN -> getBlockAt(getX(), getY() - 1, getZ());
                case UP -> getBlockAt(getX(), getY() + 1, getZ());
                case NORTH -> getBlockAt(getX(), getY(), getZ() - 1);
                case SOUTH -> getBlockAt(getX(), getY(), getZ() + 1);
                case EAST -> getBlockAt(getX() + 1, getY(), getZ());
                case WEST -> getBlockAt(getX() - 1, getY(), getZ());
                default -> throw new UnsupportedOperationException(face.name());
            };
        }
        @Override public BlockState getState() {
            final BlockData saved = data.clone();
            return new BlockState() {
                @Override public Material getType() { return saved.getMaterial(); }
                @Override public BlockData getBlockData() { return saved.clone(); }
                @Override public Block getBlock() { return TestBlock.this; }
                @Override public boolean update(boolean force, boolean physics) {
                    setBlockData(saved, physics);
                    return true;
                }
            };
        }
    }

    public static final class TestPlayer extends Player {
        private final UUID id = UUID.randomUUID();
        private final Map<String, MetadataValue> metadata = new HashMap<>();
        public Location location;
        public boolean onGround;
        public boolean sneaking;
        private TestPlayer(Location location) { this.location = location; }
        @Override public UUID getUniqueId() { return id; }
        @Override public int getEntityId() { return id.hashCode(); }
        @Override public World getWorld() { return location.getWorld(); }
        @Override public Location getLocation() { return location.clone(); }
        @Override public boolean isOnGround() { return onGround; }
        @Override public boolean isSneaking() { return sneaking; }
        @Override public BoundingBox getBoundingBox() {
            return new BoundingBox(new Vector(location.getX() - 0.3, location.getY(), location.getZ() - 0.3),
                    new Vector(location.getX() + 0.3, location.getY() + 1.8, location.getZ() + 0.3));
        }
        @Override public void setMetadata(String key, MetadataValue value) { metadata.put(key, value); }
        @Override public boolean hasMetadata(String key) { return metadata.containsKey(key); }
        @Override public List<MetadataValue> getMetadata(String key) {
            return metadata.containsKey(key) ? List.of(metadata.get(key)) : List.of();
        }
        @Override public void removeMetadata(String key, Object owner) { metadata.remove(key); }
    }
}

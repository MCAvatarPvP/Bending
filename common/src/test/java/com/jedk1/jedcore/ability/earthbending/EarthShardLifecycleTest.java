package com.jedk1.jedcore.ability.earthbending;

import com.jedk1.jedcore.configuration.JedCoreConfig;
import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.*;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.TempFallingBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class EarthShardLifecycleTest {
    @TempDir Path directory;
    private final FakeWorld world = new FakeWorld();
    private final FakePlayer player = new FakePlayer();
    private Object previousPlatform;
    private Config previousConfig;
    private com.jedk1.jedcore.configuration.Config previousJedConfig;
    private Object previousAttributes;
    private Object previousFlightHandler;
    private Set<String> previousEarthBlocks;
    private Map<String, Config> previousConfigSources;
    private final Map<Field, Object> previousShardSettings = new HashMap<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        previousPlatform = field(Platform.class, "current").get(null);
        previousConfig = ConfigManager.defaultConfig;
        previousJedConfig = JedCoreConfig.config;
        previousConfigSources = new HashMap<>(PredictionConfigSync.sources());
        PKEventBus events = proxy(PKEventBus.class, (p, method, args) -> null);
        Platform.install(proxy(ProjectKorraPlatform.class, (p, method, args) -> switch (method.getName()) {
            case "events" -> events;
            case "dataFolder" -> directory;
            case "logger" -> Logger.getLogger("EarthShardLifecycleTest");
            case "materials" -> (PKMaterials) material -> material == Material.STONE;
            default -> throw new AssertionError(method);
        }));
        ConfigManager.defaultConfig = new Config(directory.resolve("config.yml").toFile());
        ConfigManager.defaultConfig.set("Properties.Earth.EarthBlocks", List.of("STONE"));
        JedCoreConfig.config = new com.jedk1.jedcore.configuration.Config(directory.resolve("jedcore.yml").toFile());
        String prefix = "Abilities.Earth.EarthShard.";
        JedCoreConfig.config.set(prefix + "Enabled", true);
        JedCoreConfig.config.set(prefix + "PrepareRange", 5);
        JedCoreConfig.config.set(prefix + "AbilityRange", 30);
        JedCoreConfig.config.set(prefix + "MaxShards", 3);
        JedCoreConfig.config.set(prefix + "AnimationSpeed", 1.0);
        JedCoreConfig.config.set(prefix + "Cooldown", 5000);
        JedCoreConfig.config.set(prefix + "Damage.Normal", 1.0);
        JedCoreConfig.config.set(prefix + "Damage.Metal", 1.0);
        JedCoreConfig.config.set(prefix + "MaxDistance", 0);
        JedCoreConfig.config.set(prefix + "AbilityCollisionRadius", 1.0);
        JedCoreConfig.config.set(prefix + "EntityCollisionRadius", 1.0);
        JedCoreConfig.config.set(prefix + "WaitForShards", true);
        JedCoreConfig.config.set(prefix + "WaitForOffset", 1);
        JedCoreConfig.config.set(prefix + "ShootBuffer", 5000);
        Set<String> earthBlocks = (Set<String>) field(ElementalAbility.class, "EARTH_BLOCKS").get(null);
        previousEarthBlocks = new HashSet<>(earthBlocks);
        earthBlocks.add("STONE");
        for (Field setting : EarthShard.class.getDeclaredFields()) {
            if (Modifier.isStatic(setting.getModifiers()) && !Modifier.isFinal(setting.getModifiers())) {
                setting.setAccessible(true);
                previousShardSettings.put(setting, setting.get(null));
            }
        }
        Map<Class<?>, Object> attributes = (Map<Class<?>, Object>) field(CoreAbility.class, "ATTRIBUTE_FIELDS").get(null);
        previousAttributes = attributes.put(EarthShard.class, new HashMap<>());
        var flightConstructor = FlightHandler.class.getDeclaredConstructor();
        flightConstructor.setAccessible(true);
        Map<Class<?>, Object> managers = (Map<Class<?>, Object>) field(Manager.class, "MANAGERS").get(null);
        previousFlightHandler = managers.put(FlightHandler.class, flightConstructor.newInstance());
        BendingPlayer bPlayer = new BendingPlayer(player) {
            @Override public boolean canBend(CoreAbility ability) { return true; }
            @Override public boolean canBendIgnoreCooldowns(CoreAbility ability) { return true; }
            @Override public void addCooldown(String name, long duration, boolean database) { }
        };
        BendingPlayer.getPlayers().put(player.getUniqueId(), bPlayer);
        BendingPlayer.getOfflinePlayers().put(player.getUniqueId(), bPlayer);
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void tearDown() throws Exception {
        for (EarthShard shard : CoreAbility.getAbilities(player, EarthShard.class)) shard.remove();
        TempFallingBlock.removeAllFallingBlocks();
        TempBlock.removeAll();
        RegionProtection.clearCache(player);
        BendingPlayer.getPlayers().remove(player.getUniqueId());
        BendingPlayer.getOfflinePlayers().remove(player.getUniqueId());
        Map<Class<?>, Object> attributes = (Map<Class<?>, Object>) field(CoreAbility.class, "ATTRIBUTE_FIELDS").get(null);
        if (previousAttributes == null) attributes.remove(EarthShard.class);
        else attributes.put(EarthShard.class, previousAttributes);
        Map<Class<?>, Object> managers = (Map<Class<?>, Object>) field(Manager.class, "MANAGERS").get(null);
        if (previousFlightHandler == null) managers.remove(FlightHandler.class);
        else managers.put(FlightHandler.class, previousFlightHandler);
        Set<String> earthBlocks = (Set<String>) field(ElementalAbility.class, "EARTH_BLOCKS").get(null);
        earthBlocks.clear();
        if (previousEarthBlocks != null) earthBlocks.addAll(previousEarthBlocks);
        ConfigManager.defaultConfig = previousConfig;
        JedCoreConfig.config = previousJedConfig;
        Map<String, Config> sources = (Map<String, Config>) field(PredictionConfigSync.class, "SOURCES").get(null);
        sources.clear();
        sources.putAll(previousConfigSources);
        for (var setting : previousShardSettings.entrySet()) setting.getKey().set(null, setting.getValue());
        field(Platform.class, "current").set(null, previousPlatform);
    }

    @Test
    void preparingWhileAnEarlierProjectileLivesCreatesAThrowableNewInstance() {
        EarthShard previous = new EarthShard(player);
        finishRise(previous);
        EarthShard.throwShard(player);
        TempFallingBlock projectile = onlyFallingBlock(previous);
        // The next input is accepted after the five-second cooldown while
        // this projectile remains alive, as it can in client prediction.
        previous.alignPredictedStart(5001);

        player.sourceX = 3;
        EarthShard next = new EarthShard(player);

        assertTrue(next.isStarted(), "a flying projectile must not capture the new preparation");
        assertFalse(previous.isRemoved(), "the previous projectile retains its normal lifetime");
        assertSame(projectile, onlyFallingBlock(previous));
        assertSame(next, onlyFallingBlock(next).getAbility());
        finishRise(next);
        assertNotNull(TempBlock.get(world.getBlockAt(3, 67, 0)));
        EarthShard.throwShard(player);
        assertEquals(1, TempFallingBlock.getFromAbility(next).size(), "the new held shard can be thrown");
        assertNull(TempBlock.get(world.getBlockAt(3, 67, 0)));
        assertFalse(projectile.getFallingBlock().isDead());
    }

    @Test
    void anotherSourceStillJoinsTheUnthrownPreparation() {
        EarthShard first = new EarthShard(player);
        player.sourceX = 3;
        EarthShard extra = new EarthShard(player);

        assertFalse(extra.isStarted());
        assertEquals(List.of(first), List.copyOf(CoreAbility.getAbilities(player, EarthShard.class)));
        assertEquals(2, TempFallingBlock.getFromAbility(first).size());
    }

    @Test
    void riseTickFallbackStillCreatesTheHeldBlockWithoutNativeMovement() {
        EarthShard shard = new EarthShard(player);
        for (int tick = 0; tick < 10; tick++) shard.progress();

        assertTrue(TempFallingBlock.getFromAbility(shard).isEmpty());
        assertNotNull(TempBlock.get(world.getBlockAt(1, 67, 0)));
        assertFalse(shard.isRemoved());
    }

    @Test
    void furtherSourcesJoinTheNewPreparationWhileTheOldProjectileLives() {
        EarthShard previous = new EarthShard(player);
        finishRise(previous);
        EarthShard.throwShard(player);
        player.sourceX = 3;
        EarthShard next = new EarthShard(player);
        player.sourceX = 5;
        new EarthShard(player);

        assertEquals(2, CoreAbility.getAbilities(player, EarthShard.class).size());
        assertEquals(1, TempFallingBlock.getFromAbility(previous).size());
        assertEquals(2, TempFallingBlock.getFromAbility(next).size());
    }

    @Test
    void directSelectionCannotAddRisingBlocksToAThrownInstance() {
        EarthShard shard = new EarthShard(player);
        finishRise(shard);
        EarthShard.throwShard(player);
        TempFallingBlock projectile = onlyFallingBlock(shard);
        player.sourceX = 3;
        shard.select();

        assertSame(projectile, onlyFallingBlock(shard));
        assertNull(TempBlock.get(world.getBlockAt(3, 64, 0)));
    }

    @Test
    void removedInstancesCannotRaiseNewShards() {
        EarthShard shard = new EarthShard(player);
        shard.remove();
        player.sourceX = 3;
        shard.raiseEarthBlock(world.getBlockAt(3, 64, 0));

        assertTrue(TempFallingBlock.getFromAbility(shard).isEmpty());
        assertNull(TempBlock.get(world.getBlockAt(3, 64, 0)));
    }

    private void finishRise(EarthShard shard) {
        FakeFallingBlock falling = (FakeFallingBlock) onlyFallingBlock(shard).getFallingBlock();
        falling.location.setY(67.4);
        shard.progress();
        assertTrue(falling.isDead());
        assertTrue(TempFallingBlock.getFromAbility(shard).isEmpty());
    }

    private static TempFallingBlock onlyFallingBlock(EarthShard shard) {
        List<TempFallingBlock> falling = TempFallingBlock.getFromAbility(shard);
        assertEquals(1, falling.size());
        return falling.getFirst();
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private final class FakePlayer extends Player {
        private final UUID id = UUID.randomUUID();
        private int sourceX = 1;
        @Override public UUID getUniqueId() { return id; }
        @Override public String getName() { return id.toString(); }
        @Override public boolean isOnline() { return true; }
        @Override public Location getLocation() { return new Location(world, 0.5, 65, 0.5); }
        @Override public Location getEyeLocation() { return getLocation().add(0, 1.62, 0); }
        @Override public World getWorld() { return world; }
        @Override public Block getTargetBlock(Set<Material> transparent, int distance) { return world.getBlockAt(sourceX, 64, 0); }
    }

    private static final class FakeWorld extends World {
        private final Map<String, FakeBlock> blocks = new HashMap<>();
        @Override public List<Player> getPlayers() { return List.of(); }
        @Override public Block getBlockAt(int x, int y, int z) {
            return blocks.computeIfAbsent(x + ":" + y + ":" + z, ignored -> new FakeBlock(this, x, y, z));
        }
        @Override public FallingBlock spawnFallingBlock(Location location, BlockData data) {
            return new FakeFallingBlock(location, data);
        }
    }

    private static final class FakeFallingBlock extends FallingBlock {
        private final Location location;
        private final BlockData data;
        private boolean dead;
        private Vector velocity;
        private FakeFallingBlock(Location location, BlockData data) { this.location = location.clone(); this.data = data.clone(); }
        @Override public Location getLocation() { return location.clone(); }
        @Override public World getWorld() { return location.getWorld(); }
        @Override public BlockData getBlockData() { return data.clone(); }
        @Override public void setVelocity(Vector value) { velocity = value.clone(); }
        @Override public Vector getVelocity() { return velocity.clone(); }
        @Override public void remove() { dead = true; }
        @Override public boolean isDead() { return dead; }
    }

    private static final class FakeBlock extends Block {
        private final Location location;
        private BlockData data;
        private FakeBlock(World world, int x, int y, int z) {
            location = new Location(world, x, y, z);
            data = (y == 64 ? Material.STONE : Material.AIR).createBlockData();
        }
        @Override public Location getLocation() { return location.clone(); }
        @Override public World getWorld() { return location.getWorld(); }
        @Override public Material getType() { return data.getMaterial(); }
        @Override public BlockData getBlockData() { return data.clone(); }
        @Override public void setBlockData(BlockData value, boolean physics) { data = value.clone(); }
        @Override public Block getRelative(BlockFace face) { return getRelative(face, 1); }
        @Override public Block getRelative(BlockFace face, int distance) {
            Vector direction = switch (face) {
                case UP -> new Vector(0, 1, 0);
                case DOWN -> new Vector(0, -1, 0);
                case EAST -> new Vector(1, 0, 0);
                case WEST -> new Vector(-1, 0, 0);
                case NORTH -> new Vector(0, 0, -1);
                case SOUTH -> new Vector(0, 0, 1);
                default -> new Vector();
            };
            return location.clone().add(direction.multiply(distance)).getBlock();
        }
        @Override public BlockState getState() {
            BlockData snapshot = data.clone();
            return new BlockState() {
                @Override public Block getBlock() { return FakeBlock.this; }
                @Override public Material getType() { return snapshot.getMaterial(); }
                @Override public BlockData getBlockData() { return snapshot.clone(); }
                @Override public boolean update(boolean force, boolean physics) { data = snapshot.clone(); return true; }
            };
        }
    }
}

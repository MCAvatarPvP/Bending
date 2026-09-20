package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.PKMaterials;
import com.projectkorra.projectkorra.platform.PKServer;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockState;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import com.projectkorra.projectkorra.util.Information;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class EarthBlastLifecycleTest {
    @TempDir Path directory;
    private final World world = new World() {
        @Override public String getName() { return "earth-blast-test"; }
    };
    private final FakeBlock origin = new FakeBlock(0, Material.STONE);
    private final FakeBlock projectile = new FakeBlock(8, Material.STONE);
    private Object previousPlatform;
    private Config previousConfig;
    private Map<String, Config> previousConfigSources;
    private Map<Class<?>, Object> attributes;
    private Object previousAttributes;
    private Object previousSharedPlayer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        previousPlatform = field(Platform.class, "current").get(null);
        previousConfig = ConfigManager.defaultConfig;
        previousConfigSources = new HashMap<>(PredictionConfigSync.sources());
        PKEventBus events = proxy(PKEventBus.class, (p, method, args) -> null);
        PKServer server = proxy(PKServer.class, (p, method, args) -> {
            if (method.getName().equals("minecraftVersion")) return "1.21.11";
            throw new AssertionError(method);
        });
        Platform.install(proxy(ProjectKorraPlatform.class, (p, method, args) -> switch (method.getName()) {
            case "events" -> events;
            case "server" -> server;
            case "dataFolder" -> directory;
            case "logger" -> Logger.getLogger("EarthBlastLifecycleTest");
            case "materials" -> (PKMaterials) material -> material == Material.STONE;
            default -> throw new AssertionError(method);
        }));
        ConfigManager.defaultConfig = new Config(directory.resolve("config.yml").toFile());
        ConfigManager.defaultConfig.set("Properties.Earth.RevertEarthbending", true);
        attributes = (Map<Class<?>, Object>) field(CoreAbility.class, "ATTRIBUTE_FIELDS").get(null);
        previousAttributes = attributes.put(TestEarthBlast.class, new HashMap<>());
        Field player = field(CoreAbility.class, "player");
        if (Modifier.isStatic(player.getModifiers())) previousSharedPlayer = player.get(null);
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void tearDown() throws Exception {
        EarthAbility.getMovedEarth().remove(origin);
        EarthAbility.getMovedEarth().remove(projectile);
        EarthAbility.getTempAirLocations().values().removeIf(info -> origin.equals(info.getBlock()));
        if (previousAttributes == null) attributes.remove(TestEarthBlast.class);
        else attributes.put(TestEarthBlast.class, previousAttributes);
        ConfigManager.defaultConfig = previousConfig;
        Map<String, Config> sources = (Map<String, Config>) field(PredictionConfigSync.class, "SOURCES").get(null);
        sources.clear();
        sources.putAll(previousConfigSources);
        Field player = field(CoreAbility.class, "player");
        if (Modifier.isStatic(player.getModifiers())) player.set(null, previousSharedPlayer);
        field(Platform.class, "current").set(null, previousPlatform);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void endedProjectileCannotEraseAReplacementDuringDelayedReversion(boolean replaceSourceHole) {
        Information moved = snapshot(origin);
        EarthAbility.addTempAirBlock(origin);
        EarthAbility.getMovedEarth().put(projectile, moved);
        TestEarthBlast blast = launchedBlast();

        blast.remove();

        assertEquals(Material.AIR, projectile.getType());
        assertFalse(EarthAbility.getMovedEarth().containsKey(projectile));
        projectile.setType(Material.GOLD_BLOCK);
        if (replaceSourceHole) origin.setType(Material.DIAMOND_BLOCK);

        // These are the restore operations a delayed RevertChecker can reach.
        EarthAbility.revertBlock(projectile);
        EarthAbility.revertAirBlock(origin);

        assertEquals(Material.GOLD_BLOCK, projectile.getType());
        assertEquals(replaceSourceHole ? Material.DIAMOND_BLOCK : Material.STONE, origin.getType());
        assertTrue(EarthAbility.getTempAirLocations().values().stream()
                .noneMatch(info -> origin.equals(info.getBlock())));
    }

    @Test
    void repeatedRemovalCannotEraseABlockPlacedAfterTheBlastEnded() {
        TestEarthBlast blast = launchedBlast();
        blast.remove();
        projectile.setType(Material.GOLD_BLOCK);

        blast.remove();

        assertEquals(Material.GOLD_BLOCK, projectile.getType());
    }

    @Test
    void cancellingAnUnlaunchedSelectionKeepsItsMovedEarthRestoration() {
        Information moved = snapshot(origin);
        EarthAbility.getMovedEarth().put(projectile, moved);
        TestEarthBlast blast = new TestEarthBlast();
        blast.setSourceBlock(projectile);
        blast.setSourcetype(Material.STONE);
        projectile.setType(Material.COBBLESTONE);

        blast.remove();

        assertEquals(Material.STONE, projectile.getType());
        assertSame(moved, EarthAbility.getMovedEarth().get(projectile));
    }

    private TestEarthBlast launchedBlast() {
        TestEarthBlast blast = new TestEarthBlast();
        blast.setSourceBlock(projectile);
        blast.setSourcetype(Material.STONE);
        blast.setDestination(new Location(world, 16, 64, 0));
        return blast;
    }

    private static Information snapshot(Block block) {
        Information info = new Information();
        info.setBlock(block);
        info.setState(block.getState());
        return info;
    }

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    private static final class TestEarthBlast extends EarthBlast {
        private TestEarthBlast() { super(new FakePlayer()); }
        @Override public boolean isEnabled() { return false; }
        @Override public boolean prepare() { return false; }
    }

    private static final class FakePlayer extends Player {
        private final UUID id = UUID.randomUUID();
        @Override public UUID getUniqueId() { return id; }
    }

    private final class FakeBlock extends Block {
        private final int x;
        private Material type;

        private FakeBlock(int x, Material type) {
            this.x = x;
            this.type = type;
        }

        @Override public Material getType() { return type; }
        @Override public void setType(Material type) { this.type = type; }
        @Override public void setType(Material type, boolean physics) { this.type = type; }
        @Override public World getWorld() { return world; }
        @Override public Location getLocation() { return new Location(world, x, 64, 0); }
        @Override public BlockState getState() {
            Material captured = type;
            return new BlockState() {
                @Override public Material getType() { return captured; }
                @Override public Block getBlock() { return FakeBlock.this; }
                @Override public boolean update(boolean force, boolean physics) {
                    type = captured;
                    return true;
                }
            };
        }
    }
}

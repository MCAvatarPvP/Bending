package com.projectkorra.projectkorra.fabric.client.prediction.impl;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.util.ComboManager;
import com.projectkorra.projectkorra.ability.util.PassiveManager;
import com.projectkorra.projectkorra.configuration.Config;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.fabric.client.PredictionClient;
import com.projectkorra.projectkorra.fabric.prediction.protocol.PredictionPayloads.InputKind;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.PKMaterials;
import com.projectkorra.projectkorra.platform.PKServer;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.waterbending.passive.FastSwim;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.reflect.ReflectionFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FastSwimPredictionTest {
    @TempDir Path directory;
    private Object previousPlatform;
    private Config previousConfig;
    private CoreAbility previousPrototype;
    private Manager previousFlightHandler;
    private Object previousCooldownListener;
    private Vector velocity = new Vector();
    private int velocityWrites;
    private Set<String> permissions = Set.of("bending.water.passive", "bending.ability.fastswim");
    private BlockData sampledData = Material.WATER.createBlockData();
    private final World world = new World() {
        @Override public Block getBlockAt(int x, int y, int z) {
            return new Block() {
                @Override public Material getType() { return sampledData.getMaterial(); }
                @Override public BlockData getBlockData() { return sampledData; }
            };
        }
    };
    private final Player player = new Player() {
        private final UUID uuid = UUID.randomUUID();
        @Override public UUID getUniqueId() { return uuid; }
        @Override public String getName() { return uuid.toString(); }
        @Override public boolean hasPermission(String node) { return permissions.contains(node.toLowerCase(Locale.ROOT)); }
        @Override public World getWorld() { return world; }
        @Override public Location getLocation() { return new Location(world, 0, 64, 0); }
        @Override public Location getEyeLocation() { return getLocation().add(0, 1.62, 0); }
        @Override public boolean isSneaking() { return PredictionClient.serverVisibleSneaking(null); }
        @Override public void setVelocity(Vector value) { velocity = value; velocityWrites++; }
    };
    private Harness runtime;

    @BeforeEach
    void setup() throws Exception {
        previousPlatform = field(Platform.class, "current").get(null);
        previousConfig = ConfigManager.defaultConfig;
        final PKEventBus events = (PKEventBus) Proxy.newProxyInstance(
                PKEventBus.class.getClassLoader(), new Class<?>[]{PKEventBus.class},
                (proxy, method, args) -> null);
        final PKServer server = (PKServer) Proxy.newProxyInstance(
                PKServer.class.getClassLoader(), new Class<?>[]{PKServer.class},
                (proxy, method, args) -> method.getName().equals("minecraftVersion") ? "1.21.11" : null);
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(
                ProjectKorraPlatform.class.getClassLoader(), new Class<?>[]{ProjectKorraPlatform.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "dataFolder" -> directory;
                    case "events" -> events;
                    case "materials" -> (PKMaterials) material -> false;
                    case "server" -> server;
                    case "logger" -> Logger.getLogger("FastSwimPredictionTest");
                    default -> throw new AssertionError(method);
                }));
        ConfigManager.defaultConfig = new Config(directory.resolve("config.yml").toFile());
        ConfigManager.defaultConfig.set("Abilities.Water.Passive.FastSwim.SpeedFactor", 0.9);
        ConfigManager.defaultConfig.set("Abilities.Water.Passive.FastSwim.Enabled", true);
        final var constructor = FlightHandler.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        previousFlightHandler = managers().put(FlightHandler.class, constructor.newInstance());
        final FastSwim prototype = (FastSwim) ReflectionFactory.getReflectionFactory()
                .newConstructorForSerialization(FastSwim.class, CoreAbility.class.getConstructor())
                .newInstance();
        previousPrototype = prototypes().put(FastSwim.class, prototype);
        final BendingPlayer bending = new BendingPlayer(player);
        bending.getElements().add(Element.WATER);
        BendingPlayer.getPlayers().put(player.getUniqueId(), bending);
        runtime = new Harness(bending);
        previousCooldownListener = field(CooldownSync.class, "listener").get(null);
        CooldownSync.install(runtime);
    }

    @AfterEach
    void cleanup() throws Exception {
        for (FastSwim active : CoreAbility.getAbilities(player, FastSwim.class)) active.remove();
        if (previousPrototype == null) prototypes().remove(FastSwim.class);
        else prototypes().put(FastSwim.class, previousPrototype);
        BendingPlayer.getPlayers().remove(player.getUniqueId());
        if (previousFlightHandler == null) managers().remove(FlightHandler.class);
        else managers().put(FlightHandler.class, previousFlightHandler);
        field(CooldownSync.class, "listener").set(null, previousCooldownListener);
        ComboManager.clearRuntimeState();
        for (var input : ComboManager.getRecentlyUsedAbilities(player, Integer.MAX_VALUE)) {
            ComboManager.removeRecentAbility(player, input);
        }
        RegionProtection.clearCache(player);
        ConfigManager.defaultConfig = previousConfig;
        field(Platform.class, "current").set(null, previousPlatform);
    }

    @Test
    void shiftWithoutABoundAbilityPredictsFastSwim() {
        assertEquals("FastSwim", runtime.inputAbilityName0(0, "", InputKind.SNEAK_START));
    }

    @Test
    void existingPassiveDoesNotDropTheNextShiftPress() {
        new FastSwim(player, true);
        assertTrue(CoreAbility.hasAbility(player, FastSwim.class));

        assertEquals("FastSwim", runtime.inputAbilityName0(0, "", InputKind.SNEAK_START),
                "a retained passive must not turn shift into an unpredicted empty-slot action");
    }

    @Test
    void missingWaterElementDoesNotPredictFastSwim() {
        runtime.bendingPlayer.getElements().clear();
        assertEquals("", runtime.inputAbilityName0(0, "", InputKind.SNEAK_START));
    }

    @Test
    void missingPassivePermissionMatchesReportedFailureAndResolvedGrantRestoresPrediction() {
        permissions = Set.of("bending.ability.fastswim");
        final CoreAbility passive = CoreAbility.getAbility(FastSwim.class);

        assertTrue(runtime.bendingPlayer.canUsePassive(passive));
        assertFalse(PassiveManager.hasPassive(player, passive));
        assertFalse(CoreAbility.hasAbility(player, FastSwim.class));
        assertEquals("", runtime.inputAbilityName0(0, "", InputKind.SNEAK_START));

        permissions = Set.of("bending.water.passive", "bending.ability.fastswim");
        assertTrue(PassiveManager.hasPassive(player, passive));
        assertEquals("FastSwim", runtime.inputAbilityName0(0, "", InputKind.SNEAK_START));
        assertPredictedSwimAndRelease();
    }

    @Test
    void passivePermissionDoesNotBypassDeniedAbilityPermission() {
        permissions = Set.of("bending.water.passive");
        assertFalse(PassiveManager.hasPassive(player, CoreAbility.getAbility(FastSwim.class)));
        assertEquals("", runtime.inputAbilityName0(0, "", InputKind.SNEAK_START));
    }

    @Test
    void predictedShiftProducesSwimmingVelocityBeforeAnyServerReceipt() {
        assertPredictedSwimAndRelease();
    }

    private void assertPredictedSwimAndRelease() {
        assertFalse(CooldownSync.isAuthoritative());
        assertTrue(runtime.input0(1L, InputKind.SNEAK_START, 0,
                new PredictionClient.ServerPose(0, 64, 0, 0, 0, 1.62), false));
        PredictionClient.withInputSneaking(true, CoreAbility::progressAll);

        assertEquals(1, velocityWrites);
        assertEquals(0.9, velocity.getZ(), 1.0E-9);
        assertEquals(0.0, velocity.getX(), 1.0E-9);
        assertEquals(0.0, velocity.getY(), 1.0E-9);

        PredictionClient.withInputSneaking(false, CoreAbility::progressAll);
        assertFalse(CoreAbility.hasAbility(player, FastSwim.class));
        assertEquals(1, velocityWrites, "releasing shift must stop the local swim boost");
    }

    @Test
    void sneakingOnDryLandDoesNotApplySwimmingVelocity() {
        sampledData = Material.AIR.createBlockData();
        assertTrue(runtime.input0(1L, InputKind.SNEAK_START, 0,
                new PredictionClient.ServerPose(0, 64, 0, 0, 0, 1.62), false));
        PredictionClient.withInputSneaking(true, CoreAbility::progressAll);
        assertEquals(0, velocityWrites);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        final Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<? extends CoreAbility>, CoreAbility> prototypes() throws Exception {
        return (Map<Class<? extends CoreAbility>, CoreAbility>) field(CoreAbility.class, "ABILITIES_BY_CLASS").get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<? extends Manager>, Manager> managers() throws Exception {
        return (Map<Class<? extends Manager>, Manager>) field(Manager.class, "MANAGERS").get(null);
    }

    private static final class Harness extends ExactPredictionApiLifecycle {
        private Harness(BendingPlayer player) {
            this.bendingPlayer = player;
            this.ready = true;
        }
    }
}

package me.moros.hyperion.abilities.earthbending;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Manager;
import com.projectkorra.projectkorra.ability.Ability;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.activation.AbilityActivationManager;
import com.projectkorra.projectkorra.event.AbilityDamageEntityEvent;
import com.projectkorra.projectkorra.event.AbilityStartEvent;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.PKPlayers;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.prediction.movement.VelocitySync;
import com.projectkorra.projectkorra.prediction.state.PredictionConfigSync;
import com.projectkorra.projectkorra.prediction.state.CooldownSync;
import com.projectkorra.projectkorra.util.Cooldown;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.support.AbilityWorld;
import com.projectkorra.projectkorra.util.FlightHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import me.moros.hyperion.configuration.Config;
import me.moros.hyperion.configuration.ConfigManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MetalCableTest {
    @TempDir Path directory;
    private final AbilityWorld world = new AbilityWorld();
    private final AbilityWorld.TestPlayer player = world.player(0.5, 64, 0.5);
    private final Map<Entity, Vector> velocities = new HashMap<>();
    private final List<AbilityDamageEntityEvent> damage = new ArrayList<>();
    private Map<Class<?>, Object> attributes;
    private Map<Class<?>, Object> managers;
    private Map<String, com.projectkorra.projectkorra.configuration.Config> configs;
    private Map<String, com.projectkorra.projectkorra.configuration.Config> oldConfigs;
    private Object previousAttributes;
    private Object previousFlight;
    private boolean hadFlight;
    private Config previousConfig;
    private boolean canBend = true;
    private boolean cancelStart;
    private int cooldowns;
    private final VelocitySync.Listener listener = (ability, entity, velocity) -> {
        assertInstanceOf(MetalCable.class, ability);
        velocities.put(entity, velocity.clone());
    };

    @BeforeEach @SuppressWarnings("unchecked") void setup() throws Exception {
        attributes = (Map<Class<?>, Object>) field(CoreAbility.class, "ATTRIBUTE_FIELDS").get(null);
        previousAttributes = attributes.put(MetalCable.class, new HashMap<>());
        managers = (Map<Class<?>, Object>) field(Manager.class, "MANAGERS").get(null);
        hadFlight = managers.containsKey(FlightHandler.class);
        previousFlight = managers.put(FlightHandler.class, null);
        configs = (Map<String, com.projectkorra.projectkorra.configuration.Config>) field(PredictionConfigSync.class, "SOURCES").get(null);
        oldConfigs = new HashMap<>(configs);
        previousConfig = ConfigManager.config;
        ProjectKorraPlatform delegate = Platform.current();
        PKEventBus events = (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(),
                new Class<?>[]{PKEventBus.class}, (proxy, method, args) -> {
                    if (method.getName().equals("call")) {
                        if (args[0] instanceof AbilityDamageEntityEvent hit) { damage.add(hit); hit.setCancelled(true); }
                        if (args[0] instanceof AbilityStartEvent start && cancelStart) start.setCancelled(true);
                    }
                    return null;
                });
        PKPlayers players = (PKPlayers) Proxy.newProxyInstance(PKPlayers.class.getClassLoader(),
                new Class<?>[]{PKPlayers.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "onlinePlayers" -> List.of();
                    case "isExternalSpectator", "isBedrockPlayer" -> false;
                    default -> null;
                });
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "events" -> events;
                    case "players" -> players;
                    case "dataFolder" -> directory;
                    default -> method.invoke(delegate, args);
                }));
        ConfigManager.config = new Config("config.yml");
        final Config config = ConfigManager.getConfig();
        config.set("Abilities.Earth.MetalCable.Enabled", true);
        config.set("Abilities.Earth.MetalCable.Damage", 4);
        config.set("Abilities.Earth.MetalCable.BlockSpeed", 1.4);
        config.set("Abilities.Earth.MetalCable.Cooldown", 5000);
        config.set("Abilities.Earth.MetalCable.Range", 30);
        config.set("Abilities.Earth.MetalCable.RegenDelay", 10000);
        config.set("Abilities.Earth.MetalCable.Speed", 1.8);
        config.set("Abilities.Earth.MetalCable.CollisionRadius", 0.6);
        com.projectkorra.projectkorra.configuration.ConfigManager.getConfig().set("Properties.DamageMultiplier", 1);
        BendingPlayer.getPlayers().put(player.getUniqueId(), new BendingPlayer(player) {
            @Override public boolean canBend(CoreAbility ability) {
                return canBend && !isOnInputCooldown(ability.getName());
            }
            @Override public boolean canBendIgnoreCooldowns(CoreAbility ability) { return canBend; }
            @Override public boolean canBendIgnoreBindsCooldowns(CoreAbility ability) { return canBend; }
            @Override public void addCooldown(Ability ability) {
                MetalCableTest.this.cooldowns++;
                super.addCooldown(ability);
            }
        });
        player.eyeHeight = 1.62;
        VelocitySync.install(listener);
        RegionProtection.clearCache();
    }

    @AfterEach void cleanup() throws Exception {
        for (MetalCable cable : CoreAbility.getAbilities(MetalCable.class)) cable.remove();
        VelocitySync.clear(listener);
        TempBlock.removeAll();
        RegionProtection.unloadPlugin("MetalCableTest");
        RegionProtection.clearCache();
        BendingPlayer.getPlayers().remove(player.getUniqueId());
        ConfigManager.config = previousConfig;
        configs.clear();
        configs.putAll(oldConfigs);
        if (previousAttributes == null) attributes.remove(MetalCable.class);
        else attributes.put(MetalCable.class, previousAttributes);
        if (hadFlight) managers.put(FlightHandler.class, previousFlight);
        else managers.remove(FlightHandler.class);
        world.close();
    }

    @Test void hookTravelsToTheExactBlockFaceAndStaysPinned() {
        Block block = stone(0, 65, 10);
        MetalCable cable = new MetalCable(player);
        assertTrue(cable.isStarted());
        assertEquals(1, cooldowns);
        cable.progress();
        assertTrue(cable.getLocation().getZ() < 3, "the hook travels rather than attaching instantly");
        ticks(cable, 6);
        assertFalse(cable.isRemoved());
        assertEquals(10, cable.getLocation().getZ(), 1.0E-8);
        Location anchor = cable.getLocation().clone();
        player.location.setX(1.5);
        ticks(cable, 3);
        assertEquals(anchor, cable.getLocation());
        assertEquals(anchor, cable.getLocations().getLast());
        assertEquals(Material.STONE, block.getType());
        assertTrue(velocities.get(player).getZ() > 0.7);
        assertFalse(world.displays.isEmpty());
    }

    @Test void thinCollisionShapeCannotBeSkippedAtHighSpeed() {
        var block = world.getBlockAt(0, 65, 7);
        block.setType(Material.STONE);
        block.setCollisionBoxes(new BoundingBox(new Vector(0, 65, 7.48), new Vector(1, 66, 7.52)));
        ConfigManager.getConfig().set("Abilities.Earth.MetalCable.Speed", 14);
        MetalCable cable = new MetalCable(player);
        cable.progress();
        assertFalse(cable.isRemoved());
        assertEquals(7.48, cable.getLocation().getZ(), 1.0E-8);
    }

    @Test void entityAttachmentAndPullFollowTheCurrentTargetWithoutATickOfLag() {
        var target = world.player(0.5, 64, 9);
        MetalCable cable = new MetalCable(player);
        ticks(cable, 6);
        Vector offset = cable.getLocation().toVector().subtract(target.location.toVector());
        target.location.setX(6);
        target.location.setZ(3);
        cable.progress();
        assertEquals(0, cable.getLocation().toVector().distanceSquared(target.location.toVector().add(offset)), 1.0E-8);
        Vector expected = cable.getLocation().toVector().subtract(player.location.toVector()).normalize().multiply(0.8);
        assertEquals(0, expected.distanceSquared(velocities.get(player)), 1.0E-8);
        assertEquals(cable.getLocation(), cable.getLocations().getLast());
    }

    @Test void sneakingPullsTheGrabbedEntityWithOwnedVelocity() {
        var target = world.player(0.5, 64, 9);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 6);
        assertFalse(cable.isRemoved());
        assertFalse(velocities.containsKey(player));
        assertEquals(0.8, velocities.get(target).length(), 1.0E-8);
        assertTrue(velocities.get(target).getZ() < 0);
        assertTrue(damage.isEmpty(), "the metal hook itself is harmless");
    }

    @Test void grabbedTerrainSpawnsAtItsSourceAndTheRopeStaysOnItsFace() {
        Block source = stone(0, 65, 10);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 6);
        assertFalse(cable.isRemoved());
        AbilityWorld.TestDisplay block = grabbedDisplay();
        assertEquals(Material.AIR, source.getType());
        assertSame(cable, TempBlock.get(source).getAbility().orElseThrow());
        assertTrue(block.location.getZ() > 9.5, "spawn at the source, not the previous hook tick");
        Vector offset = cable.getLocation().toVector().subtract(block.location.toVector());
        assertEquals(-0.47, offset.getZ(), 1.0E-8);
        assertEquals(0.94F, block.transformation.scale().x, 1.0E-6);
        for (int i = 0; i < 5; i++) {
            cable.progress();
            assertFalse(cable.isRemoved());
            assertEquals(0, cable.getLocation().toVector().subtract(block.location.toVector()).distanceSquared(offset), 1.0E-8);
            assertEquals(cable.getLocation(), cable.getLocations().getLast());
        }
        assertTrue(block.location.getZ() < 9);
        cable.remove();
        assertFalse(block.isValid());
        TempBlock.removeAll();
        assertEquals(Material.STONE, source.getType());
    }

    @Test void canExtractAndHoldABlockSurroundedByASolidWall() {
        for (int x = -2; x <= 2; x++) {
            for (int y = 63; y <= 68; y++) stone(x, y, 10);
        }
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 8);
        assertFalse(cable.isRemoved(), "extract outward before steering past the adjacent blocks");
        AbilityWorld.TestDisplay block = grabbedDisplay();
        ticks(cable, 45);
        assertFalse(cable.isRemoved(), "keep the block held instead of destroying it at close range");
        assertTrue(block.location.getZ() < 4);
        assertTrue(block.location.getZ() > 2);
        assertEquals(Material.STONE, world.getBlockAt(0, 66, 10).getType());
        assertEquals(Material.STONE, world.getBlockAt(1, 65, 10).getType());
    }

    @Test void floorPickupLiftsClearBeforePullingAndRemainsReadyToThrow() {
        for (int x = -4; x <= 4; x++) {
            for (int z = -2; z <= 15; z++) stone(x, 63, z);
        }
        player.location.setPitch(25);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 35);
        assertFalse(cable.isRemoved());
        AbilityWorld.TestDisplay block = grabbedDisplay();
        assertTrue(block.location.getY() >= 64.47, "a floor block must be lifted out of its neighbors");
        cable.attemptLaunchTarget();
        assertTrue(block.isValid());
        assertEquals(1, world.displays.stream().filter(Entity::isValid).count());
    }

    @Test void clickThrowsTheDisplayAndOnlyTheBlockRemains() {
        stone(0, 65, 10);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 7);
        AbilityWorld.TestDisplay block = grabbedDisplay();
        Location before = block.getLocation();
        AbilityActivationManager.beginTracking();
        new MetalCable(player);
        var handled = AbilityActivationManager.finishTrackingResult();
        assertTrue(handled.handled());
        assertTrue(handled.affectedAbilities().contains(cable));
        assertEquals(1, world.displays.stream().filter(Entity::isValid).count());
        cable.progress();
        assertTrue(block.location.getZ() > before.getZ() + 1);
        assertEquals(1, cooldowns);
        ticks(cable, 90);
        assertTrue(cable.isRemoved());
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
    }

    @Test void thrownDisplayUsesSweptDamageAndStopsAtTheFirstTarget() {
        stone(0, 65, 10);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 7);
        var target = world.player(0.5, 64.5, 14);
        var behind = world.player(0.5, 64.5, 18);
        cable.attemptLaunchTarget();
        ticks(cable, 12);
        assertTrue(cable.isRemoved());
        assertEquals(1, damage.size());
        assertSame(target, damage.getFirst().getEntity());
        assertEquals(4, damage.getFirst().getDamage());
        assertTrue(damage.stream().noneMatch(event -> event.getEntity().equals(behind)));
    }

    @Test void thrownBlockCannotDamageThroughAWall() {
        stone(0, 65, 10);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 7);
        for (int y = 64; y <= 68; y++) stone(0, y, 12);
        world.player(0.5, 64.5, 14);
        cable.attemptLaunchTarget();
        ticks(cable, 20);
        assertTrue(cable.isRemoved());
        assertTrue(damage.isEmpty());
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
    }

    @Test void launchingDoesNotPreventAnotherCableAfterCooldown() {
        stone(0, 65, 10);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 7);
        cable.attemptLaunchTarget();
        expireCooldown();
        MetalCable next = new MetalCable(player);
        assertTrue(next.isStarted());
        assertFalse(cable.isRemoved());
        assertEquals(2, cooldowns);
    }

    @Test void hookCannotGrabAnEntityThroughTerrain() {
        stone(0, 65, 5);
        var target = world.player(0.5, 64, 8);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 4);
        assertFalse(velocities.containsKey(target));
        assertNotNull(grabbedDisplay());
    }

    @Test void protectedSourceIsNeitherGrabbedNorChanged() {
        Block source = stone(0, 65, 10);
        RegionProtection.registerRegionProtection("MetalCableTest", (p, loc, ability) -> loc.getBlockZ() >= 10);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 7);
        assertTrue(cable.isRemoved());
        assertEquals(Material.STONE, source.getType());
        assertTrue(TempBlock.getActiveLayers().isEmpty());
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
    }

    @Test void changingAnchorOrBlockingTheCableRemovesAllDisplays() {
        Block source = stone(0, 65, 10);
        MetalCable cable = new MetalCable(player);
        ticks(cable, 7);
        source.setType(Material.AIR);
        cable.progress();
        assertTrue(cable.isRemoved());
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));

        source.setType(Material.STONE);
        expireCooldown();
        MetalCable other = new MetalCable(player);
        ticks(other, 7);
        stone(0, 65, 5);
        other.progress();
        assertTrue(other.isRemoved());
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void losingBendingCleansUpBothAttachedAndThrownDisplays(boolean thrown) {
        stone(0, 65, 10);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 7);
        if (thrown) cable.attemptLaunchTarget();
        canBend = false;
        cable.progress();
        cable.remove();
        assertTrue(cable.isRemoved());
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
    }

    @Test void missedHookExpiresAtItsRange() {
        MetalCable cable = new MetalCable(player);
        ticks(cable, 25);
        assertTrue(cable.isRemoved());
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
        assertTrue(damage.isEmpty());
    }

    @Test void cancellingActivationDoesNotLeakDisplaysOrApplyCooldown() {
        cancelStart = true;
        MetalCable cable = new MetalCable(player);
        assertTrue(cable.isRemoved());
        assertEquals(0, cooldowns);
        assertTrue(world.displays.stream().noneMatch(Entity::isValid));
    }

    @Test void freshCastIsBlockedUntilItsCooldownExpiresEvenAfterTheCableEnds() {
        MetalCable cable = new MetalCable(player);
        long expiresAt = BendingPlayer.getBendingPlayer(player).getCooldown("MetalCable");
        assertTrue(expiresAt > System.currentTimeMillis());
        cable.remove();
        MetalCable blocked = new MetalCable(player);
        assertFalse(blocked.isStarted());
        assertEquals(expiresAt, BendingPlayer.getBendingPlayer(player).getCooldown("MetalCable"));
        assertEquals(1, cooldowns);

        expireCooldown();
        assertTrue(new MetalCable(player).isStarted());
        assertEquals(2, cooldowns);
    }

    @Test void throwDuringCooldownDoesNotResetItAndCannotStartAnotherCable() {
        stone(0, 65, 10);
        player.sneaking = true;
        MetalCable cable = new MetalCable(player);
        ticks(cable, 7);
        long expiresAt = BendingPlayer.getBendingPlayer(player).getCooldown("MetalCable");

        CooldownSync.runInputVeto(player.getUniqueId(), List.of("MetalCable"), () -> new MetalCable(player));
        assertEquals(1, cable.getLocations().size(), "the second click throws despite the cast cooldown");
        assertFalse(new MetalCable(player).isStarted(), "a flying block does not bypass the fresh-cast gate");
        assertEquals(expiresAt, BendingPlayer.getBendingPlayer(player).getCooldown("MetalCable"));
        assertEquals(1, cooldowns);
    }

    @Test void cancelledPreStartVisualCannotLeaveAnIndexedDeadCable() {
        RegionProtection.registerRegionProtection("MetalCableTest", (p, loc, ability) -> loc.getY() > 65);
        MetalCable rejected = new MetalCable(player);
        assertTrue(rejected.isRemoved());
        assertFalse(rejected.isStarted());
        assertTrue(CoreAbility.getAbilities(player, MetalCable.class).isEmpty());
        assertEquals(0, cooldowns);

        RegionProtection.unloadPlugin("MetalCableTest");
        RegionProtection.clearCache();
        assertTrue(new MetalCable(player).isStarted(), "a rejected visual must not swallow future casts");
    }

    private void expireCooldown() {
        BendingPlayer.getBendingPlayer(player).getCooldowns().put("MetalCable",
                new Cooldown(System.currentTimeMillis() - 60_000L, false));
    }

    private Block stone(int x, int y, int z) {
        Block block = world.getBlockAt(x, y, z);
        block.setType(Material.STONE);
        return block;
    }

    private AbilityWorld.TestDisplay grabbedDisplay() {
        return world.displays.stream().filter(Entity::isValid)
                .filter(display -> display.data.getMaterial() == Material.STONE).findFirst().orElseThrow();
    }

    private void ticks(MetalCable cable, int count) {
        for (int i = 0; i < count && !cable.isRemoved(); i++) AbilityExecutionContext.run(cable, cable::progress);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

package com.projectkorra.projectkorra.platform.bukkit;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.OfflinePlayer;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class BukkitMCWrapperTest {
    @Test
    void blocksUseTheSuppliedHandleAndKeepCoordinateBasedMapLookups() {
        org.bukkit.World world = stub(org.bukkit.World.class, Map.of("getUID", UUID.randomUUID()));
        org.bukkit.block.Block original = block(world, 12, org.bukkit.Material.STONE);
        org.bukkit.block.Block replacement = block(world, 12, org.bukkit.Material.DIRT);
        Block first = BukkitMC.block(original);
        Block repeated = BukkitMC.block(original);
        Block current = BukkitMC.block(replacement);

        assertNotSame(first, repeated);
        assertEquals(first, repeated);
        assertSame(replacement, current.handle());
        assertEquals(Material.DIRT, current.getType());
        Map<Block, String> tracked = new HashMap<>();
        tracked.put(first, "temporary block");
        assertEquals("temporary block", tracked.remove(current));
        assertTrue(tracked.isEmpty());
        assertNotEquals(first, BukkitMC.block(block(world, 13, org.bukkit.Material.STONE)));
        org.bukkit.World otherWorld = stub(org.bukkit.World.class, Map.of("getUID", UUID.randomUUID()));
        assertNotEquals(first, BukkitMC.block(block(otherWorld, 12, org.bukkit.Material.STONE)));
    }

    @ParameterizedTest
    @MethodSource("entityTypes")
    void entitiesKeepTheirSubtypeAndMapIdentityWithoutReusingStaleHandles(
            Class<? extends org.bukkit.entity.Entity> nativeType, Class<? extends Entity> commonType) {
        UUID uuid = UUID.randomUUID();
        org.bukkit.entity.Entity original = stub(nativeType, Map.of("getUniqueId", uuid));
        org.bukkit.entity.Entity replacement = stub(nativeType, Map.of("getUniqueId", uuid));
        Entity first = BukkitMC.entity(original);
        Entity repeated = BukkitMC.entity(original);
        Entity current = BukkitMC.entity(replacement);

        assertInstanceOf(commonType, first);
        assertInstanceOf(commonType, current);
        assertNotSame(first, repeated);
        assertSame(original, first.handle());
        assertSame(replacement, current.handle());
        assertEquals(first, repeated);
        assertEquals(first, current);
        assertEquals(current, first);
        Map<Entity, String> tracked = new HashMap<>();
        tracked.put(first, "tracked entity");
        assertEquals("tracked entity", tracked.remove(current));
        assertTrue(tracked.isEmpty());
        assertNotEquals(first, BukkitMC.entity(stub(nativeType, Map.of("getUniqueId", UUID.randomUUID()))));
    }

    private static Stream<Arguments> entityTypes() {
        return Stream.of(
                Arguments.of(org.bukkit.entity.Entity.class, Entity.class),
                Arguments.of(org.bukkit.entity.LivingEntity.class, LivingEntity.class),
                Arguments.of(org.bukkit.entity.Player.class, Player.class),
                Arguments.of(org.bukkit.entity.ArmorStand.class, ArmorStand.class),
                Arguments.of(org.bukkit.entity.FallingBlock.class, FallingBlock.class),
                Arguments.of(org.bukkit.entity.Arrow.class, Arrow.class),
                Arguments.of(org.bukkit.entity.ShulkerBullet.class, ShulkerBullet.class),
                Arguments.of(org.bukkit.entity.Snowball.class, Snowball.class),
                Arguments.of(org.bukkit.entity.Item.class, Item.class));
    }

    @Test
    void specializedEntryPointsAgreeWithEntityConversion() {
        org.bukkit.entity.Player player = stub(org.bukkit.entity.Player.class, Map.of("getUniqueId", UUID.randomUUID()));
        Player first = BukkitMC.player(player);
        assertNotSame(first, BukkitMC.player(player));
        assertEquals(first, BukkitMC.living(player));
        assertEquals(first, BukkitMC.offline(player));
        assertInstanceOf(Player.class, BukkitMC.offline(player));
        org.bukkit.entity.ArmorStand stand = stub(org.bukkit.entity.ArmorStand.class, Map.of("getUniqueId", UUID.randomUUID()));
        assertInstanceOf(ArmorStand.class, BukkitMC.living(stand));
        assertEquals(BukkitMC.entity(stand), BukkitMC.living(stand));
        org.bukkit.entity.FallingBlock falling = stub(org.bukkit.entity.FallingBlock.class, Map.of("getUniqueId", UUID.randomUUID()));
        assertNotSame(BukkitMC.falling(falling), BukkitMC.falling(falling));
        assertEquals(BukkitMC.entity(falling), BukkitMC.falling(falling));
    }

    @Test
    void offlinePlayersKeepUuidMapLookupsAndUseCurrentData() {
        UUID uuid = UUID.randomUUID();
        org.bukkit.OfflinePlayer original = stub(org.bukkit.OfflinePlayer.class,
                Map.of("getUniqueId", uuid, "getName", "old name"));
        org.bukkit.OfflinePlayer replacement = stub(org.bukkit.OfflinePlayer.class,
                Map.of("getUniqueId", uuid, "getName", "current name"));
        OfflinePlayer first = BukkitMC.offline(original);
        OfflinePlayer current = BukkitMC.offline(replacement);

        assertNotSame(first, BukkitMC.offline(original));
        assertSame(replacement, current.handle());
        assertEquals("current name", current.getName());
        assertEquals(first, current);
        Map<OfflinePlayer, String> tracked = new HashMap<>();
        tracked.put(first, "player data");
        assertEquals("player data", tracked.remove(current));
        assertTrue(tracked.isEmpty());
        assertNotEquals(first, BukkitMC.offline(stub(org.bukkit.OfflinePlayer.class,
                Map.of("getUniqueId", UUID.randomUUID()))));
        assertNull(BukkitMC.offline(stub(org.bukkit.OfflinePlayer.class, new HashMap<>())).getPlayer());
    }

    @Test
    void spawnedCloudsRemainFindableThroughEntityLookups() {
        org.bukkit.entity.AreaEffectCloud cloud = stub(org.bukkit.entity.AreaEffectCloud.class,
                Map.of("getUniqueId", UUID.randomUUID()));
        org.bukkit.World world = stub(org.bukkit.World.class,
                Map.of("getUID", UUID.randomUUID(), "spawn", cloud));
        AreaEffectCloud spawned = BukkitMC.world(world).spawn(null, AreaEffectCloud.class);
        Entity found = BukkitMC.entity(cloud);

        assertEquals(spawned, found);
        assertEquals(found, spawned);
        Map<Entity, String> tracked = new HashMap<>();
        tracked.put(spawned, "cloud");
        assertEquals("cloud", tracked.remove(found));
        assertTrue(tracked.isEmpty());
    }

    @Test
    void nullInputsStillReturnNull() {
        assertNull(BukkitMC.block(null));
        assertNull(BukkitMC.entity(null));
        assertNull(BukkitMC.player(null));
        assertNull(BukkitMC.offline(null));
        assertNull(BukkitMC.living(null));
        assertNull(BukkitMC.falling(null));
    }

    private static org.bukkit.block.Block block(org.bukkit.World world, int x, org.bukkit.Material material) {
        return stub(org.bukkit.block.Block.class, Map.of(
                "getWorld", world, "getX", x, "getY", 64, "getZ", -8, "getType", material));
    }

    private static <T> T stub(Class<T> type, Map<String, Object> values) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> type.getSimpleName() + values;
                    case "getPlayer" -> null;
                    default -> {
                        if (!values.containsKey(method.getName())) throw new AssertionError(method.toString());
                        yield values.get(method.getName());
                    }
                }));
    }
}

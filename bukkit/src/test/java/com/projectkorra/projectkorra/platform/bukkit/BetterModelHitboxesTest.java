package com.projectkorra.projectkorra.platform.bukkit;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.util.BoundingBox;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.colliders.Ray;
import com.projectkorra.projectkorra.util.colliders.Sphere;
import kr.toxicity.model.api.nms.HitBox;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.junit.jupiter.api.*;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BetterModelHitboxesTest {
    private final Map<UUID, org.bukkit.entity.Entity> loaded = new HashMap<>();
    private final List<org.bukkit.entity.Entity> nearby = new ArrayList<>();
    private final List<String> mutations = new ArrayList<>();
    private World world;
    private Field serverField;
    private Object previousServer;
    private NativeEntity host, head, companion;

    @BeforeEach
    void setup() throws ReflectiveOperationException {
        UUID worldId = UUID.randomUUID();
        world = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUID" -> worldId;
                    case "getPlayers" -> List.of();
                    case "getNearbyEntities" -> nearby.stream()
                            .filter(entity -> entity.getBoundingBox().overlaps((org.bukkit.util.BoundingBox) args[0])).toList();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> worldId.hashCode();
                    default -> throw new AssertionError(method);
                });
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[]{Server.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getEntity")) return loaded.get(args[0]);
                    throw new AssertionError(method);
                });
        serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        previousServer = serverField.get(null);
        serverField.set(null, server);
        host = nativeEntity(org.bukkit.entity.Cow.class, null, false,
                new org.bukkit.util.BoundingBox(-.5, 64, -.5, .5, 65.5, .5));
        head = nativeEntity(org.bukkit.entity.ArmorStand.class, host, false,
                new org.bukkit.util.BoundingBox(3, 68, -1, 5, 70, 1));
        companion = nativeEntity(org.bukkit.entity.Interaction.class, host, false,
                new org.bukkit.util.BoundingBox(2.5, 68, -1.5, 5.5, 70, 1.5));
        companion.seatId = head.id;
        companion.vehicle = head.entity;
    }

    @AfterEach
    void cleanup() throws IllegalAccessException {
        if (serverField != null) serverField.set(null, previousServer);
    }

    @Test
    void upperBodyIsAnAbilityTargetOutsideTheCowHitboxAndEffectsReachTheCow() {
        var center = new com.projectkorra.projectkorra.platform.mc.Location(BukkitMC.world(world), 4, 69, 0);
        var targets = GeneralMethods.getEntitiesAroundPoint(center, .25);
        assertEquals(1, targets.size());
        var target = assertInstanceOf(LivingEntity.class, targets.getFirst());
        assertFalse(target instanceof ArmorStand);
        assertEquals(host.id, target.getUniqueId());
        assertSame(host.entity, target.handle());
        assertEquals(3, target.getBoundingBox().getMinX());
        target.damage(4);
        target.setVelocity(new Vector(1, 2, 3));
        target.setFireTicks(40);
        assertEquals(List.of("cow:damage", "cow:velocity", "cow:fire"), mutations);
        // Native event conversion still exposes its real entity, not the combat-only owner view.
        assertInstanceOf(ArmorStand.class, BukkitMC.entity(head.entity));
    }

    @Test
    void overlappingBodyPartsAndCompanionsProduceOnlyOneDamageTarget() {
        nativeEntity(org.bukkit.entity.ArmorStand.class, host, false,
                new org.bukkit.util.BoundingBox(2, 67, -2, 6, 69.5, 2));
        var targets = query(-2, 63, -3, 7, 71, 3);
        assertEquals(1, targets.size());
        assertSame(host.entity, targets.getFirst().handle());
        assertEquals(BukkitMC.entity(host.entity), targets.getFirst());
        assertEquals(targets.getFirst(), BukkitMC.entity(host.entity));
        assertEquals(BukkitMC.entity(host.entity).hashCode(), targets.getFirst().hashCode());
        assertTrue(targets.getFirst().getBoundingBox().getMaxY() > 65.5);
    }

    @Test
    void rayAndSphereUseIndividualBodyBoundsRatherThanABoxFillingTheGap() {
        var origin = new com.projectkorra.projectkorra.platform.mc.Location(BukkitMC.world(world), 0, 69, 0);
        var ray = new Ray(origin, new Vector(1, 0, 0), 7);
        assertEquals(1, ray.getEntities(entity -> entity instanceof LivingEntity).size());
        var gap = new com.projectkorra.projectkorra.platform.mc.Location(BukkitMC.world(world), 1.5, 67, 0);
        assertTrue(new Sphere(gap, .3).getEntities(entity -> entity instanceof LivingEntity).isEmpty());
        // Only the companion overlaps here; its bigger proxy box must not grant a hit.
        assertTrue(query(2.55, 68.5, -.1, 2.8, 69, .1).isEmpty());
    }

    @Test
    void aMissedPartCannotDiscardAnotherPartThatPassesTheNarrowCollisionTest() {
        var high = nativeEntity(org.bukkit.entity.ArmorStand.class, host, false,
                new org.bukkit.util.BoundingBox(3, 74, -1, 5, 76, 1));
        var box = box(2, 67, -2, 6, 77, 2);
        var targets = BukkitMC.world(world).getNearbyEntities(box, e -> e.getBoundingBox().getMinY() >= 74);
        assertEquals(1, targets.size());
        assertEquals(high.bounds.getMinY(), targets.iterator().next().getBoundingBox().getMinY());
    }

    @Test
    void mountAnchorsAndTheirCompanionsAreNotDamageTargets() {
        nearby.clear();
        NativeEntity seat = nativeEntity(org.bukkit.entity.ArmorStand.class, host, true,
                new org.bukkit.util.BoundingBox(0, 75, 0, 1, 76, 1));
        NativeEntity proxy = nativeEntity(org.bukkit.entity.Interaction.class, host, true, seat.bounds);
        proxy.vehicle = seat.entity;
        proxy.seatId = seat.id;
        assertTrue(query(-1, 74, -1, 2, 77, 2).isEmpty());
    }

    @Test
    void staleDeletedAndDetachedPartsCannotRemainAsGhostTargets() {
        head.valid = false;
        assertTrue(query(3, 68, -1, 5, 70, 1).isEmpty());
        head.valid = true;
        host.valid = false;
        assertTrue(query(3, 68, -1, 5, 70, 1).isEmpty());
        host.valid = true;
        loaded.remove(host.id);
        assertTrue(query(3, 68, -1, 5, 70, 1).isEmpty());
    }

    @Test
    void modeledPlayersRetainPlayerTypeForPvpAndSpectatorChecks() {
        nearby.clear();
        NativeEntity player = nativeEntity(org.bukkit.entity.Player.class, null, false, host.bounds);
        nativeEntity(org.bukkit.entity.ArmorStand.class, player, false, head.bounds);
        var result = query(3, 68, -1, 5, 70, 1);
        assertInstanceOf(com.projectkorra.projectkorra.platform.mc.entity.Player.class, result.getFirst());
        assertEquals(player.id, result.getFirst().getUniqueId());
    }

    @Test
    void detachedCompanionDoesNotGrantAnIndependentHitbox() {
        nearby.clear();
        nearby.add(companion.entity);
        companion.vehicle = null;
        assertTrue(query(3, 68, -1, 5, 70, 1).isEmpty());
    }

    @Test
    void partsLeftInAnotherWorldCannotDamageTheMovedHost() {
        host.dimension = (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[]{World.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("equals")) return proxy == args[0];
                    throw new AssertionError(method);
                });
        assertTrue(query(3, 68, -1, 5, 70, 1).isEmpty());
    }

    @Test
    void ordinaryArmorStandsKeepTheirNormalTypeWithoutBetterModel() {
        nearby.clear();
        var decoration = nativeEntity(org.bukkit.entity.ArmorStand.class, null, false, head.bounds);
        var targets = query(3, 68, -1, 5, 70, 1);
        assertEquals(1, targets.size());
        assertInstanceOf(ArmorStand.class, targets.getFirst());
        assertEquals(decoration.id, targets.getFirst().getUniqueId());
        assertFalse(BetterModelHitboxes.isHitbox(decoration.entity));
    }

    private List<Entity> query(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new ArrayList<>(BukkitMC.world(world).getNearbyEntities(box(x1, y1, z1, x2, y2, z2), null));
    }

    private static BoundingBox box(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new BoundingBox(new Vector(x1, y1, z1), new Vector(x2, y2, z2));
    }

    private NativeEntity nativeEntity(Class<? extends org.bukkit.entity.Entity> type, NativeEntity owner,
                                      boolean mountable, org.bukkit.util.BoundingBox bounds) {
        NativeEntity nativeEntity = new NativeEntity();
        nativeEntity.bounds = bounds;
        nativeEntity.dimension = world;
        Class<?>[] interfaces = owner == null ? new Class<?>[]{type} : new Class<?>[]{type, HitBox.class};
        nativeEntity.entity = (org.bukkit.entity.Entity) Proxy.newProxyInstance(type.getClassLoader(), interfaces,
                (proxy, method, args) -> switch (method.getName()) {
                    case "getUniqueId" -> nativeEntity.id;
                    case "uuid" -> nativeEntity.seatId;
                    case "source" -> (HitBox.Source) () -> owner.id;
                    case "mountController" -> (HitBox.Controller) () -> mountable;
                    case "getWorld" -> nativeEntity.dimension;
                    case "getBoundingBox" -> bounds.clone();
                    case "getLocation", "getEyeLocation" -> new org.bukkit.Location(world,
                            bounds.getCenterX(), bounds.getMinY(), bounds.getCenterZ());
                    case "getVehicle" -> nativeEntity.vehicle;
                    case "isValid" -> nativeEntity.valid;
                    case "isDead", "isMarker" -> false;
                    case "isInvisible" -> true;
                    case "getName", "toString" -> type.getSimpleName();
                    case "getEntityId" -> nativeEntity.id.hashCode();
                    case "damage" -> { mutations.add(type.getSimpleName().toLowerCase() + ":damage"); yield null; }
                    case "setVelocity" -> { mutations.add(type.getSimpleName().toLowerCase() + ":velocity"); yield null; }
                    case "setFireTicks" -> { mutations.add(type.getSimpleName().toLowerCase() + ":fire"); yield null; }
                    case "getGameMode" -> org.bukkit.GameMode.SURVIVAL;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> nativeEntity.id.hashCode();
                    default -> throw new AssertionError(method);
                });
        nearby.add(nativeEntity.entity);
        loaded.put(nativeEntity.id, nativeEntity.entity);
        return nativeEntity;
    }

    private static class NativeEntity {
        final UUID id = UUID.randomUUID();
        UUID seatId = id;
        boolean valid = true;
        org.bukkit.entity.Entity entity, vehicle;
        World dimension;
        org.bukkit.util.BoundingBox bounds;
    }
}

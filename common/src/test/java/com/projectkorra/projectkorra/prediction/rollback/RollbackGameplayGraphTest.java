package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackGameplayGraphTest {
    private Map<Class<?>, Object> attributes;
    private Map<Class<?>, Object> previousAttributes;
    @org.junit.jupiter.api.BeforeEach
    @SuppressWarnings("unchecked")
    void isolateDefinitions() throws Exception {
        // This fixture negotiates only the installed main inventory, not abilities declared by other test classes.
        var field = com.projectkorra.projectkorra.ability.CoreAbility.class.getDeclaredField("ATTRIBUTE_FIELDS");
        field.setAccessible(true); attributes = (Map<Class<?>, Object>) field.get(null);
        previousAttributes = new HashMap<>(attributes); attributes.clear();
    }
    @org.junit.jupiter.api.AfterEach
    void restoreDefinitions() { attributes.clear(); attributes.putAll(previousAttributes); }
    private static final class Person extends Player {
        final UUID id; final World world;
        Person(int id, World world) { this.id = new UUID(0, id); this.world = world; }
        @Override public UUID getUniqueId() { return id; }
        @Override public World getWorld() { return world; }
        @Override public boolean isOnline() { return true; }
    }
    @Test void assembledSchemasRebindRosterEffectsAndExplicitServiceIdentities() {
        var liveWorld = new World(); var privateWorld = new World();
        var liveA = new Person(1, liveWorld); var privateA = new Person(1, privateWorld);
        var live = new RollbackRosterBindings(liveWorld, List.of(liveA, new Person(2, liveWorld)));
        var replica = new RollbackRosterBindings(privateWorld, List.of(privateA, new Person(2, privateWorld)));
        var configuration = RollbackConfiguration.prepare(RollbackConfiguration.captureData(Map.of()), Map.of());
        CommonAbilityLifecycleListener.Effects sourceEffects = effect -> { throw new AssertionError("live output"); };
        var outputs = new ArrayList<CommonAbilityLifecycleListener.Effect>();
        CommonAbilityLifecycleListener.Effects privateEffects = outputs::add;
        var sourceListener = new CommonAbilityLifecycleListener(sourceEffects);
        var privateListener = new CommonAbilityLifecycleListener(privateEffects);
        Object sourceService = new Object(), privateService = new Object();
        var installed = RollbackGameplayCatalog.installed(getClass().getClassLoader());
        var limits = new RollbackGraphCodec.Limits(1000, 10000, 1000000, 10000);
        var sender = RollbackGameplayGraph.create(installed, limits, RollbackGameplayGraph.Side.LIVE,
                live, configuration, sourceListener, List.of(new RollbackGraphCodec.Binding("service", Object.class, sourceService)), new RollbackGraphViews());
        var receiver = RollbackGameplayGraph.create(installed, limits, RollbackGameplayGraph.Side.PRIVATE,
                replica, configuration, privateListener, List.of(new RollbackGraphCodec.Binding("service", Object.class, privateService)), new RollbackGraphViews());
        var copied = receiver.decode(sender.encode(List.of(liveA, liveWorld, sourceEffects, sourceService, sourceListener)));
        assertSame(privateA, copied.get(0)); assertSame(privateWorld, copied.get(1));
        assertSame(privateEffects, copied.get(2)); assertSame(privateService, copied.get(3));
        assertNotSame(sourceListener, copied.get(4));
        var event = new CommonAbilityLifecycleListener.ConsoleCommand("example");
        ((CommonAbilityLifecycleListener.Effects) copied.get(2)).emit(event);
        assertEquals(List.of(event), outputs);
        assertThrows(IllegalArgumentException.class, () -> RollbackGameplayGraph.create(installed, limits,
                RollbackGameplayGraph.Side.PRIVATE, replica, configuration, privateListener,
                List.of(new RollbackGraphCodec.Binding("world", World.class, new World())), new RollbackGraphViews()));
    }
    @Test void completeBendingImportReturnsUninstalledPrivateRootsAndRejectsRosterMismatch() {
        var sourceWorld = new World(); var destinationWorld = new World();
        var a = new Person(70001, sourceWorld); var b = new Person(70002, sourceWorld);
        var privateA = PrivateCombatRollbackTest.player(destinationWorld, 70001);
        var privateB = PrivateCombatRollbackTest.player(destinationWorld, 70002);
        var sourceRoster = new RollbackRosterBindings(sourceWorld, List.of(a, b));
        var targetRoster = new RollbackRosterBindings(destinationWorld, List.of(privateA, privateB));
        var data = RollbackConfiguration.captureData(Map.of());
        var configuration = RollbackConfiguration.prepare(data, Map.of());
        var lifecycle = new CommonAbilityLifecycleListener(effect -> { throw new AssertionError("Import invoked gameplay"); });
        var installed = RollbackGameplayCatalog.installed(getClass().getClassLoader());
        var limits = new RollbackGraphCodec.Limits(10000, 100000, 10000000, 10000);
        var sender = RollbackGameplayGraph.create(installed, limits, RollbackGameplayGraph.Side.LIVE,
                sourceRoster, configuration, lifecycle, List.of(), new RollbackGraphViews());
        var first = new com.projectkorra.projectkorra.BendingPlayer(a);
        var second = new com.projectkorra.projectkorra.BendingPlayer(b);
        first.getCooldowns().put("arbitrary-ability", new com.projectkorra.projectkorra.util.Cooldown(12345, false));
        byte[] bytes = RollbackBendingState.encode(List.of(first, second),
                new com.projectkorra.projectkorra.ability.util.CollisionManager(), List.of(lifecycle), sender);
        var imported = RollbackGameplayGraph.decode(installed, limits, sourceRoster.participants(), bytes,
                data, Map.of(), targetRoster, lifecycle, List.of());
        assertSame(privateA, imported.bending().players().get(a.getUniqueId()).getPlayer());
        assertNotSame(first, imported.bending().players().get(a.getUniqueId()));
        assertEquals(List.of(imported.configuration(), imported.bending()), imported.roots());
        assertEquals(12345, imported.bending().players().get(a.getUniqueId()).getCooldowns().get("arbitrary-ability").getCooldown());
        imported.bending().players().get(a.getUniqueId()).getCooldowns().clear();
        assertTrue(first.getCooldowns().containsKey("arbitrary-ability"));
        assertFalse(RollbackDomain.active());
        assertThrows(IllegalArgumentException.class, () -> RollbackGameplayGraph.decode(installed, limits,
                Set.of(a.getUniqueId()), bytes, data, Map.of(), targetRoster, lifecycle, List.of()));
        assertThrows(IllegalArgumentException.class, () -> RollbackGameplayGraph.decode(installed, limits,
                sourceRoster.participants(), new byte[0], data, Map.of(), targetRoster, lifecycle, List.of()));
    }}

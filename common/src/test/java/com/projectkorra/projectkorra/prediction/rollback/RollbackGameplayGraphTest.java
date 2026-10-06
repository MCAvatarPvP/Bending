package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.listener.CommonAbilityLifecycleListener;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackGameplayGraphTest {
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
}

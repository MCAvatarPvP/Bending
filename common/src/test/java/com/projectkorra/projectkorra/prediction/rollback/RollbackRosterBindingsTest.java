package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackRosterBindingsTest {
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2);
    private static final RollbackGraphCodec.Limits LIMITS = new RollbackGraphCodec.Limits(100, 1000, 100000, 1000);
    private static final class WorldView extends World {
        final Object handle; WorldView(Object handle) { this.handle = handle; }
        @Override public Object handle() { return handle; }
    }
    private static final class PlayerView extends Player {
        final UUID id; final Object handle; World world; boolean online = true;
        PlayerView(UUID id, Object handle, World world) { this.id = id; this.handle = handle; this.world = world; }
        @Override public UUID getUniqueId() { return id; }
        @Override public Object handle() { return handle; }
        @Override public World getWorld() { return world; }
        @Override public boolean isOnline() { return online; }
    }
    private static final class Holder { Player first, second; World world; Holder self; }

    @Test void repeatedNativeWrappersBecomeOnePrivateRosterBindingWithoutCapturingTheirLoaderClasses() {
        var sourceWorld = new WorldView(new Object());
        var a = new PlayerView(A, new Object(), sourceWorld); var b = new PlayerView(B, new Object(), sourceWorld);
        var source = new RollbackRosterBindings(sourceWorld, List.of(a, b));
        var privateWorld = new WorldView(new Object());
        var privateA = new PlayerView(A, new Object(), privateWorld); var privateB = new PlayerView(B, new Object(), privateWorld);
        var target = new RollbackRosterBindings(privateWorld, List.of(privateB, privateA));
        var sender = codec(source); var receiver = codec(target);
        var value = new Holder(); value.self = value;
        value.first = a; value.second = new PlayerView(A, a.handle(), new WorldView(sourceWorld.handle()));
        value.world = new WorldView(sourceWorld.handle());
        var copy = (Holder) receiver.decode(sender.encode(List.of(value), ignored -> null)).getFirst();
        assertSame(copy, copy.self); assertSame(privateA, copy.first); assertSame(copy.first, copy.second);
        assertSame(privateWorld, copy.world);
        var returned = (Holder) sender.decode(receiver.encode(List.of(copy))).getFirst();
        assertSame(a, returned.first); assertSame(sourceWorld, returned.world);
        assertNotSame(value.first, value.second, "Source wrappers remain unmodified");
    }
    @Test void rejectsUnenrolledBodiesWorldsDisconnectsAndUnregisteredNormalizerResults() {
        var world = new WorldView(new Object());
        var a = new PlayerView(A, new Object(), world); var b = new PlayerView(B, new Object(), world);
        var roster = new RollbackRosterBindings(world, List.of(a, b)); var codec = codec(roster);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(new PlayerView(A, new Object(), world))));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(new PlayerView(UUID.randomUUID(), a.handle(), world))));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(new WorldView(new Object()))));
        a.world = new WorldView(new Object());
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(a)));
        a.world = world; a.online = false;
        assertThrows(IllegalArgumentException.class, () -> codec.encode(List.of(a)));
        var catalog = new RollbackGraphCodec.Catalog(List.of(Holder.class), List.of(), List.of());
        var unbound = new RollbackGraphCodec(catalog, LIMITS, ignored -> new Object());
        assertThrows(IllegalArgumentException.class, () -> unbound.encode(List.of(new Holder())));
    }
    private static RollbackGraphCodec codec(RollbackRosterBindings roster) {
        return new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(Holder.class), List.of(), roster.bindings()), LIMITS, roster);
    }
}

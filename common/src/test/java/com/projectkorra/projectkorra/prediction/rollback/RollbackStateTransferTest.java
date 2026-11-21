package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.OfflineBendingPlayer;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.StanceAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import com.projectkorra.projectkorra.util.Cooldown;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RollbackStateTransferTest {
    private static final RollbackStateTransfer.Limits LIMITS = new RollbackStateTransfer.Limits(10_000, 100_000);

    @Test void bendingStateAndAbilityRelationshipsDetachWithoutConstructorsOrPlayerLoadHooks() throws Exception {
        World liveWorld = new World(), privateWorld = new World();
        Player live = player(new UUID(0, 1)), replica = player(live.getUniqueId());
        var bending = new BendingPlayer(live);
        bending.getElements().add(Element.AIR);
        bending.getAbilities().put(1, "ArbitraryAddon");
        var cooldown = new Cooldown(123_456, true);
        bending.getCooldowns().put("ArbitraryAddon", cooldown);
        bending.getComboCoolDowns().put("Combo", cooldown);
        Moving parent = new Moving(bending, liveWorld);
        var child = new AtomicReference<Moving>();
        AbilityExecutionContext.run(parent, () -> child.set(new Moving(bending, liveWorld)));
        parent.child = child.get();
        child.get().child = parent;
        field(BendingPlayer.class, "stance").set(bending, parent);
        int constructions = Moving.constructions;
        var collision = new Collision(parent, child.get(), true, false, parent.location, child.get().location);
        var replacements = new IdentityHashMap<Object, Object>();
        replacements.put(live, replica);
        replacements.put(liveWorld, privateWorld);
        var transfer = new RollbackStateTransfer(type -> type.getName().startsWith("com.projectkorra."), value -> {
            if (replacements.containsKey(value)) return new RollbackStateTransfer.Replacement(replacements.get(value));
            if (value instanceof Element) return new RollbackStateTransfer.Replacement(value); // Frozen registry identity.
            if (value instanceof Player || value instanceof World) throw new IllegalArgumentException("Unmapped live handle");
            return null;
        }, LIMITS);

        List<Object> result = transfer.copy(List.of(bending, parent, child.get(), collision));
        BendingPlayer copy = (BendingPlayer) result.get(0);
        Moving copiedParent = (Moving) result.get(1), copiedChild = (Moving) result.get(2);
        Collision copiedCollision = (Collision) result.get(3);
        assertEquals(constructions, Moving.constructions);
        assertSame(replica, copy.getPlayer());
        assertSame(replica, field(OfflineBendingPlayer.class, "player").get(copy));
        assertSame(copy, copiedParent.bending());
        assertSame(copy, copiedChild.bending());
        assertSame(copiedParent, copy.getStance());
        assertSame(copiedChild, copiedParent.child);
        assertSame(copiedParent, copiedChild.child);
        assertSame(copiedParent, copiedChild.getPredictionParent());
        assertTrue(copiedChild.isPredictionDescendantOf(copiedParent));
        assertFalse(copiedChild.isPredictionDescendantOf(parent));
        assertSame(privateWorld, copiedParent.location.getWorld());
        assertSame(copiedParent, copiedCollision.getAbilityFirst());
        assertSame(copiedParent.location, copiedCollision.getLocationFirst());
        assertSame(copy.getCooldowns().get("ArbitraryAddon"), copy.getComboCoolDowns().get("Combo"));
        assertNotSame(cooldown, copy.getCooldowns().get("ArbitraryAddon"));
        assertEquals(123_456, copy.getCooldowns().get("ArbitraryAddon").getCooldown());
        assertTrue(copy.getCooldowns().get("ArbitraryAddon").isDatabase());
        assertSame(Element.AIR, copy.getElements().getFirst());

        var graph = new RollbackStateGraph(value -> value instanceof Player || value instanceof World || value instanceof Element,
                ignored -> true, 10_000);
        var checkpoint = graph.capture(result, List.of());
        copiedParent.progress();
        copiedParent.progress();
        copy.getAbilities().put(1, "Changed");
        copy.getCooldowns().clear();
        assertEquals(3, copiedParent.location.getX());
        assertEquals(0, parent.location.getX());
        assertEquals("ArbitraryAddon", bending.getAbilities().get(1));
        assertSame(cooldown, bending.getCooldowns().get("ArbitraryAddon"));
        checkpoint.restore();
        assertEquals(0, copiedParent.location.getX());
        assertEquals("ArbitraryAddon", copy.getAbilities().get(1));
        copiedParent.progress();
        copiedParent.progress();
        assertEquals(List.of(1.0, 3.0), copiedParent.history);

        BendingPlayer second = (BendingPlayer) transfer.copy(List.of(bending)).getFirst();
        assertNotSame(copy.getAbilities(), second.getAbilities());
        assertNotSame(copy.getStance(), second.getStance());
    }

    @Test void cyclesArraysMutableHashKeysComparatorsAndCollectionSemanticsSurviveImport() {
        var key = new Key(31);
        var comparator = new Order(-1);
        var tree = new TreeMap<Key, Object>(comparator);
        tree.put(key, Optional.of(tree));
        var hash = new HashMap<Key, Object>();
        hash.put(key, tree);
        var accessOrder = new LinkedHashMap<String, Key>(16, 0.75f, true);
        accessOrder.put("first", key);
        accessOrder.put("second", key);
        Object[] array = new Object[6];
        array[0] = array;
        array[1] = key;
        array[2] = new WeakReference<>(key);
        array[3] = List.of(key, array);
        array[4] = new int[]{1, 2, 3};
        var atomic = new AtomicReference<>();
        atomic.set(atomic);
        array[5] = atomic;
        var imported = transfer().copy(List.of(array, hash, accessOrder, key, comparator));
        Object[] copied = (Object[]) imported.get(0);
        Key copiedKey = (Key) imported.get(3);
        var copiedHash = (Map<?, ?>) imported.get(1);
        var copiedTree = (TreeMap<?, ?>) copiedHash.get(copiedKey);
        assertNotSame(key, copiedKey);
        assertSame(copied, copied[0]);
        assertSame(copiedKey, copied[1]);
        assertSame(copiedKey, ((WeakReference<?>) copied[2]).get());
        assertSame(copied, ((List<?>) copied[3]).get(1));
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) copied[3]).clear());
        assertNotSame(array[4], copied[4]);
        assertArrayEquals((int[]) array[4], (int[]) copied[4]);
        assertSame(copied[5], ((AtomicReference<?>) copied[5]).get());
        assertSame(copiedKey, copiedTree.firstKey());
        assertSame(copiedTree, ((Optional<?>) copiedTree.get(copiedKey)).orElseThrow());
        assertSame(imported.get(4), copiedTree.comparator());
        var copiedOrder = (LinkedHashMap<?, ?>) imported.get(2);
        copiedOrder.get("first");
        assertEquals(List.of("second", "first"), new ArrayList<>(copiedOrder.keySet()));
        assertEquals(List.of("first", "second"), new ArrayList<>(accessOrder.keySet()));
        assertEquals(31, copiedKey.value);
        copiedKey.value = 77;
        assertEquals(31, key.value);
    }

    @Test void nestedKeyContainersAreFilledBeforeIndexingAndCircularHashDependenciesFail() {
        var key = new ContainerKey();
        key.parts.add("component");
        var outer = new HashMap<ContainerKey, String>();
        outer.put(key, "value");
        var result = transfer().copy(List.of(outer, key));
        assertEquals("value", ((Map<?, ?>) result.get(0)).get(result.get(1)));
        var cyclicKey = new Key(5);
        var cyclic = new HashMap<Key, Object>();
        cyclic.put(cyclicKey, "value");
        cyclicKey.extra = cyclic;
        assertThrows(IllegalStateException.class, () -> transfer().copy(List.of(cyclic)));
        assertSame(cyclic, cyclicKey.extra);
        assertEquals("value", cyclic.get(cyclicKey));
    }

    @Test void unmappedNativeStateBackedViewsAndObjectOrReferenceLimitsDoNotMutateSources() throws Exception {
        var key = new Key(8);
        key.extra = new Thread();
        assertThrows(IllegalStateException.class, () -> transfer().copy(List.of(key)));
        assertEquals(8, key.value);
        assertInstanceOf(Thread.class, key.extra);
        List<Key> list = new ArrayList<>(List.of(key));
        assertThrows(IllegalStateException.class, () -> transfer().copy(List.of(list.subList(0, 1))));
        assertThrows(IllegalStateException.class, () -> transfer().copy(List.of(Arrays.asList(key))));
        assertThrows(IllegalStateException.class, () -> transfer().copy(List.of(new Object())));
        assertThrows(IllegalStateException.class, () -> transfer().copy(List.of(new ExtraList())));
        var tiny = new RollbackStateTransfer(ignored -> true, ignored -> null, new RollbackStateTransfer.Limits(2, 2));
        assertThrows(IllegalStateException.class, () -> tiny.copy(Collections.singletonList(new long[3])));
        assertThrows(IllegalStateException.class, () -> tiny.copy(List.of("one", "two", "three")));
        var sameThread = transfer();
        Throwable failure = CompletableFuture.supplyAsync(() -> {
            try { sameThread.copy(List.of("value")); return null; }
            catch (Throwable exception) { return exception; }
        }).get();
        assertInstanceOf(IllegalStateException.class, failure);
        try (var ignored = RollbackClock.at(0, 0, 50_000_000)) {
            assertThrows(IllegalStateException.class, () -> sameThread.copy(List.of("value")));
        }
    }

    private static RollbackStateTransfer transfer() {
        return new RollbackStateTransfer(type -> type.getName().startsWith(RollbackStateTransferTest.class.getName()), ignored -> null, LIMITS);
    }
    private static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static Player player(UUID id) {
        return new Player() { @Override public UUID getUniqueId() { return id; } };
    }
    private static final class ExtraList extends ArrayList<Object> { int extra = 3; }
    private static class Key {
        int value;
        Object extra;
        Key(int value) { this.value = value; }
        @Override public int hashCode() { return value; }
        @Override public boolean equals(Object other) { return other instanceof Key key && key.value == value; }
    }
    private static final class ContainerKey {
        final HashSet<String> parts = new HashSet<>();
        @Override public int hashCode() { return parts.hashCode(); }
        @Override public boolean equals(Object other) { return other instanceof ContainerKey key && parts.equals(key.parts); }
    }
    private static final class Order implements Comparator<Key> {
        final int direction;
        Order(int direction) { this.direction = direction; }
        @Override public int compare(Key left, Key right) { return direction * Integer.compare(left.value, right.value); }
    }
    private static final class Moving extends CoreAbility implements StanceAbility {
        static int constructions;
        final Location location;
        final List<Double> history = new ArrayList<>();
        Moving child;
        int age;
        double speed = 1;
        Moving(BendingPlayer bending, World world) {
            super(null);
            constructions++;
            bPlayer = bending;
            player = bending.getPlayer();
            location = new Location(world, 0, 0, 0);
        }
        BendingPlayer bending() { return bPlayer; }
        @Override public void progress() { if (++age == 2) speed = 2; location.add(speed, 0, 0); history.add(location.getX()); }
        @Override public String getStanceName() { return "ArbitraryAddon"; }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "ArbitraryAddon"; }
        @Override public Element getElement() { return Element.AIR; }
        @Override public Location getLocation() { return location; }
    }
}

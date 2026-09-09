package com.projectkorra.projectkorra.ability;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.util.CollisionManager;
import com.projectkorra.projectkorra.attribute.AttributeCache;
import com.projectkorra.projectkorra.platform.PKEventBus;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.prediction.action.AbilityExecutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AbilityRetentionTest {
    private Object previousPlatform;
    private Map<String, AttributeCache> previousAttributes;
    private AttributeCache cache;

    @BeforeEach void setup() throws Exception {
        previousPlatform = field(Platform.class, "current").get(null);
        final PKEventBus events = (PKEventBus) Proxy.newProxyInstance(PKEventBus.class.getClassLoader(),
                new Class<?>[]{PKEventBus.class}, (proxy, method, args) -> null);
        Platform.install((ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, args) -> {
                    if (method.getName().equals("events")) return events;
                    throw new AssertionError(method);
                }));
        cache = new AttributeCache(field(TestAbility.class, "radius"), "Radius");
        previousAttributes = attributes().put(TestAbility.class, Map.of("Radius", cache));
    }

    @AfterEach void cleanup() throws Exception {
        if (previousAttributes == null) attributes().remove(TestAbility.class);
        else attributes().put(TestAbility.class, previousAttributes);
        field(Platform.class, "current").set(null, previousPlatform);
    }

    @Test void abandonedConstructorCanBeCollectedAfterRecalculatingAttributes() throws Exception {
        final WeakReference<TestAbility> abandoned = abandonedAbility();
        assertEquals(1, cache.getInitialValues().size());
        assertEquals(1, cache.getCurrentModifications().size());
        awaitCollection(abandoned);
        assertTrue(cache.getInitialValues().isEmpty());
        assertTrue(cache.getCurrentModifications().isEmpty());
    }

    @Test void liveAbilityKeepsItsOriginalAttributesAcrossCollectionAndRecalculation() throws Exception {
        final TestAbility live = new TestAbility();
        live.recalculateAttributes();
        final WeakReference<TestAbility> abandoned = abandonedAbility();
        awaitCollection(abandoned);
        live.radius = 99;
        live.recalculateAttributes();
        assertEquals(4.0, live.radius);
        assertEquals(1, cache.getInitialValues().size());
        Reference.reachabilityFence(live);
    }

    @Test void childDoesNotRetainItsFinishedParent() throws Exception {
        final Descendant result = descendant(null);
        awaitCollection(result.parent);
        assertNull(result.child.getPredictionParent());
        Reference.reachabilityFence(result.child);
    }

    @Test void collectedIntermediateParentDoesNotBreakRejectionAncestry() throws Exception {
        final TestAbility root = new TestAbility();
        final Descendant result = descendant(root);
        awaitCollection(result.parent);
        assertTrue(result.child.isPredictionDescendantOf(root));
        assertFalse(result.child.isPredictionDescendantOf(new TestAbility()));
        Reference.reachabilityFence(result.child);
    }

    @Test void idleAndStoppedCollisionManagersReleaseThePreviousFrame() throws Exception {
        final CollisionManager manager = new CollisionManager();
        populateCollisionFrame(manager);
        manager.detectCollisions();
        assertCollisionFrameEmpty(manager);
        populateCollisionFrame(manager);
        manager.stopCollisionDetection();
        assertCollisionFrameEmpty(manager);
    }

    private WeakReference<TestAbility> abandonedAbility() {
        final TestAbility ability = new TestAbility();
        ability.recalculateAttributes();
        assertFalse(ability.isStarted());
        return new WeakReference<>(ability);
    }

    private Descendant descendant(final TestAbility root) {
        final AtomicReference<TestAbility> parent = new AtomicReference<>();
        if (root == null) parent.set(new TestAbility());
        else AbilityExecutionContext.run(root, () -> parent.set(new TestAbility()));
        final AtomicReference<TestAbility> child = new AtomicReference<>();
        AbilityExecutionContext.run(parent.get(), () -> child.set(new TestAbility()));
        return new Descendant(child.get(), new WeakReference<>(parent.get()));
    }

    private record Descendant(TestAbility child, WeakReference<TestAbility> parent) { }

    private static void awaitCollection(WeakReference<?> reference) throws InterruptedException {
        for (int attempt = 0; attempt < 100 && reference.get() != null; attempt++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(reference.get(), "an otherwise unreachable ability is still strongly retained");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void populateCollisionFrame(CollisionManager manager) throws Exception {
        ((Map) field(CollisionManager.class, "locationsCache").get(manager))
                .put(new TestAbility(), List.of(new Location()));
        ((Map) field(CollisionManager.class, "entriesCache").get(manager)).put(TestAbility.class, List.of());
        ((Map) field(CollisionManager.class, "indexCache").get(manager)).put(TestAbility.class, null);
    }

    private static void assertCollisionFrameEmpty(CollisionManager manager) throws Exception {
        for (String name : List.of("locationsCache", "entriesCache", "indexCache")) {
            assertTrue(((Map<?, ?>) field(CollisionManager.class, name).get(manager)).isEmpty(), name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, Map<String, AttributeCache>> attributes() throws Exception {
        return (Map<Class<?>, Map<String, AttributeCache>>) field(CoreAbility.class, "ATTRIBUTE_FIELDS").get(null);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        final Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class TestAbility extends CoreAbility {
        private double radius = 4;
        private TestAbility() { super(null); }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "RetentionTest"; }
        @Override public Element getElement() { return Element.EARTH; }
        @Override public Location getLocation() { return null; }
    }
}

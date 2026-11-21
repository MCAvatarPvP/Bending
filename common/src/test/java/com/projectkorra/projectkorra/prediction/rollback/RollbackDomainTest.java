package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.ProjectKorraPlatform;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RollbackDomainTest {
    private static final class Registry {
        static final Map<String, Node> instances = new HashMap<>();
        static int ids;
    }
    private static final class Node { int value; }

    @Test void interleavedSessionsRestoreTheirOwnRegistryAndTheOutsideGameplayState() {
        var graph = new RollbackStateGraph(ignored -> false, ignored -> true, 100);
        var fields = RollbackStateGraph.staticFields(Registry.class, ignored -> true);
        var original = graph.capture(List.of(), fields);
        try {
            Node outside = new Node();
            outside.value = 100;
            Registry.instances.put("outside", outside);
            Registry.ids = 42;
            var first = domain(graph, "first");
            var second = domain(graph, "second");
            assertEquals(Map.of("outside", outside), Registry.instances);
            assertEquals(100, outside.value);
            assertEquals(42, Registry.ids);
            var checkpoint = first.call(() -> {
                assertEquals("first", Platform.current().id());
                assertEquals(List.of("first"), List.copyOf(Registry.instances.keySet()));
                Registry.instances.get("first").value = 7;
                Registry.ids = 8;
                return first.capture();
            });
            assertEquals(Map.of("outside", outside), Registry.instances);
            second.call(() -> {
                assertEquals("second", Platform.current().id());
                assertThrows(IllegalArgumentException.class, () -> second.restore(checkpoint));
                assertEquals(0, Registry.instances.get("second").value);
                assertEquals(0, Registry.ids);
                Registry.instances.get("second").value = 99;
                return null;
            });
            first.call(() -> {
                assertEquals(7, Registry.instances.get("first").value);
                assertEquals(8, Registry.ids);
                Registry.instances.clear();
                Registry.ids = 999;
                first.restore(checkpoint);
                assertEquals(7, Registry.instances.get("first").value);
                assertEquals(8, Registry.ids);
                return null;
            });
            second.call(() -> {
                assertEquals(99, Registry.instances.get("second").value);
                return null;
            });
            assertEquals(Map.of("outside", outside), Registry.instances);
            assertEquals(42, Registry.ids);
        } finally { original.restore(); }
    }

    @Test void failureRestoresOutsideAndPreventsContinuationOrNestedSessionEntry() {
        var graph = new RollbackStateGraph(ignored -> false, ignored -> true, 100);
        var fields = RollbackStateGraph.staticFields(Registry.class, ignored -> true);
        var original = graph.capture(List.of(), fields);
        try {
            var first = domain(graph, "first");
            var second = domain(graph, "second");
            first.call(() -> {
                assertThrows(IllegalStateException.class, () -> second.call(() -> null));
                return null;
            });
            int outsideIds = Registry.ids;
            var exception = assertThrows(IllegalArgumentException.class, () -> first.call(() -> {
                Registry.ids = 777;
                Registry.instances.clear();
                throw new IllegalArgumentException("step failed");
            }));
            assertEquals("step failed", exception.getMessage());
            assertEquals(outsideIds, Registry.ids);
            assertTrue(first.failed());
            assertThrows(IllegalStateException.class, () -> first.call(() -> null));
            assertThrows(IllegalStateException.class, first::capture);
            assertFalse(second.failed());
            second.call(() -> { assertTrue(Registry.instances.containsKey("second")); return null; });
        } finally { original.restore(); }
    }

    @Test void failedBootstrapDoesNotReplaceTheOutsideRegistriesOrPlatform() {
        var graph = new RollbackStateGraph(ignored -> false, ignored -> true, 100);
        var fields = RollbackStateGraph.staticFields(Registry.class, ignored -> true);
        var before = Map.copyOf(Registry.instances);
        int ids = Registry.ids;
        boolean installed = Platform.isInstalled();
        var platform = installed ? Platform.current() : null;
        assertThrows(IllegalArgumentException.class, () -> RollbackDomain.create(graph, fields, List.of(), platform("failed"), () -> {
            Registry.instances.clear();
            Registry.ids = 333;
            throw new IllegalArgumentException("bootstrap failed");
        }));
        assertEquals(before, Registry.instances);
        assertEquals(ids, Registry.ids);
        assertEquals(installed, Platform.isInstalled());
        if (installed) assertSame(platform, Platform.current());
    }

    private static RollbackDomain domain(RollbackStateGraph graph, String name) {
        return RollbackDomain.create(graph, RollbackStateGraph.staticFields(Registry.class, ignored -> true),
                List.of(), platform(name), () -> {
                    Registry.instances.clear();
                    Registry.instances.put(name, new Node());
                    Registry.ids = 0;
                });
    }
    private static ProjectKorraPlatform platform(String id) {
        return (ProjectKorraPlatform) Proxy.newProxyInstance(ProjectKorraPlatform.class.getClassLoader(),
                new Class<?>[]{ProjectKorraPlatform.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("id")) return id;
                    throw new AssertionError(method);
                });
    }
}

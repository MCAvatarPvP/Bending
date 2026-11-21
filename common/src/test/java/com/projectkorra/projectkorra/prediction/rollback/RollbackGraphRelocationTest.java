package com.projectkorra.projectkorra.prediction.rollback;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.TargetType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.lang.reflect.Array;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RollbackGraphRelocationTest {
    @Test void relocatedLayoutsPreserveCyclesArraysAndExternalBindingsInBothDirections() throws Exception {
        for (var prefixes : List.of(new String[]{"org.apache.commons", "commonslang3.projectkorra"},
                new String[]{"org.yaml.snakeyaml", "com.projectkorra.libs.snakeyaml"})) {
            var source = fixture(prefixes[0] + ".rollbackfixture.State");
            var target = fixture(prefixes[1] + ".rollbackfixture.State");
            var sourceService = source.getConstructor().newInstance();
            var targetService = target.getConstructor().newInstance();
            var sourceCatalog = new RollbackGraphCodec.Catalog(List.of(source), List.of(source.arrayType()),
                    List.of(new RollbackGraphCodec.Binding("service", source, sourceService)));
            var targetCatalog = new RollbackGraphCodec.Catalog(List.of(target), List.of(target.arrayType()),
                    List.of(new RollbackGraphCodec.Binding("service", target, targetService)));
            assertArrayEquals(sourceCatalog.fingerprint(), targetCatalog.fingerprint());
            assertThrows(IllegalArgumentException.class, () -> new RollbackGraphCodec.Catalog(List.of(source, target), List.of(), List.of()));
            var limits = new RollbackGraphCodec.Limits(100, 1000, 16384, 1024);
            var sender = new RollbackGraphCodec(sourceCatalog, limits); var receiver = new RollbackGraphCodec(targetCatalog, limits);
            Object original = source.getConstructor().newInstance();
            source.getField("next").set(original, original);
            source.getField("value").setInt(original, 42);
            Object array = Array.newInstance(source, 2); Array.set(array, 0, original); Array.set(array, 1, sourceService);
            var decoded = receiver.decode(sender.encode(List.of(original, array, source.arrayType(), sourceService)));
            assertSame(target, decoded.get(0).getClass());
            assertSame(decoded.get(0), target.getField("next").get(decoded.get(0)));
            assertEquals(42, target.getField("value").getInt(decoded.get(0)));
            assertSame(decoded.get(0), Array.get(decoded.get(1), 0)); assertSame(targetService, Array.get(decoded.get(1), 1));
            assertSame(target.arrayType(), decoded.get(2)); assertSame(targetService, decoded.get(3));
            var restored = sender.decode(receiver.encode(decoded));
            assertSame(source, restored.get(0).getClass());
            assertSame(restored.get(0), source.getField("next").get(restored.get(0)));
            assertSame(sourceService, restored.get(3));
        }
    }
    private static Class<?> fixture(String name) {
        return new ByteBuddy().subclass(Object.class).name(name)
                .defineField("value", int.class, Visibility.PUBLIC)
                .defineField("next", TargetType.class, Visibility.PUBLIC)
                .make().load(RollbackGraphRelocationTest.class.getClassLoader()).getLoaded();
    }

    /** Run after shadowJar with ROLLBACK_PACKAGED_JAR set to the freshly built Paper artifact. */
    @Test @EnabledIfEnvironmentVariable(named = "ROLLBACK_PACKAGED_JAR", matches = ".+")
    void packagedPaperCodecInteroperatesWithOriginalCommonsClasses() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getenv("ROLLBACK_PACKAGED_JAR")).toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            var source = org.apache.commons.lang3.tuple.MutablePair.class;
            var target = loader.loadClass("commonslang3.projectkorra.lang3.tuple.MutablePair");
            var catalog = loader.loadClass(RollbackGraphCodec.Catalog.class.getName());
            var constructor = catalog.getConstructor(java.util.Collection.class, java.util.Collection.class, java.util.Collection.class);
            var sourceCatalog = constructor.newInstance(List.of(source), List.of(), List.of());
            var targetCatalog = constructor.newInstance(List.of(target), List.of(), List.of());
            assertArrayEquals((byte[]) catalog.getMethod("fingerprint").invoke(sourceCatalog),
                    (byte[]) catalog.getMethod("fingerprint").invoke(targetCatalog));
            var codec = loader.loadClass(RollbackGraphCodec.class.getName());
            var limitsType = loader.loadClass(RollbackGraphCodec.Limits.class.getName());
            var limits = limitsType.getConstructor(int.class, int.class, int.class, int.class).newInstance(100, 1000, 16384, 1024);
            var sender = codec.getConstructor(catalog, limitsType).newInstance(sourceCatalog, limits);
            var receiver = codec.getConstructor(catalog, limitsType).newInstance(targetCatalog, limits);
            var original = source.getConstructor().newInstance(); original.left = "shared"; original.right = original;
            var wire = codec.getMethod("encode", java.util.Collection.class).invoke(sender, List.of(original));
            var copy = ((List<?>) codec.getMethod("decode", byte[].class).invoke(receiver, wire)).getFirst();
            assertSame(target, copy.getClass());
            assertEquals("shared", target.getField("left").get(copy)); assertSame(copy, target.getField("right").get(copy));
            var backWire = codec.getMethod("encode", java.util.Collection.class).invoke(receiver, List.of(copy));
            var restored = ((List<?>) codec.getMethod("decode", byte[].class).invoke(sender, backWire)).getFirst();
            assertSame(source, restored.getClass()); assertSame(restored, source.getField("right").get(restored));
        }
    }
}

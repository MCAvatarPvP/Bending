package com.projectkorra.build;

import com.sun.source.util.JavacTask;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class WrapperReferenceEqualityPluginTest {
    @TempDir Path output;

    private static final String IMPORTS = """
            import com.projectkorra.projectkorra.platform.mc.entity.*;
            import com.projectkorra.projectkorra.platform.mc.block.Block;
            import com.projectkorra.projectkorra.platform.mc.OfflinePlayer;
            import java.util.Objects;
            """;

    @ParameterizedTest
    @ValueSource(strings = {
            "player == entity", "entity != player", "player == other", "other != player",
            "block == other", "other != block", "offline == player", "player != offline",
            "((Object) player) == ((Object) entity)", "((Object) block) != other"
    })
    void rejectsWrapperReferenceComparisons(String expression) throws Exception {
        Compilation result = compile("""
                class Example {
                    boolean matches(Player player, Entity entity, Block block, OfflinePlayer offline, Object other) {
                        return %s;
                    }
                }
                """.formatted(expression));

        assertRejected(result, 1);
        assertEquals(7, result.errors().getFirst().getLineNumber());
        assertTrue(result.errors().getFirst().getMessage(null).contains("Objects.equals()"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "player == null", "null != block", "offline != null", "entity == (Object) null",
            "((Player) null) == player",
            "Objects.equals(player, entity)", "player.equals(entity)",
            "player.handle() == entity.handle()", "other == other",
            "1 == 2", "Boolean.TRUE == true", "Thread.State.NEW != Thread.State.RUNNABLE"
    })
    void permitsNullValueAndNativeIdentityChecks(String expression) throws Exception {
        Compilation result = compile("""
                class Example {
                    boolean matches(Player player, Entity entity, Block block, OfflinePlayer offline, Object other) {
                        return %s;
                    }
                }
                """.formatted(expression));
        assertTrue(result.success(), result.errors().toString());
    }

    @Test
    void rejectsGenericBoundsInheritedTypesAndLambdas() throws Exception {
        Compilation result = compile("""
                class Example<T extends Entity> {
                    static class CustomMob extends Entity { }
                    boolean generic(T first, T second) { return first == second; }
                    boolean inherited(CustomMob first, Entity second) { return first != second; }
                    java.util.function.Predicate<Entity> predicate(Player player) {
                        return candidate -> candidate == player;
                    }
                }
                """);
        assertRejected(result, 3);
    }

    @Test
    void permitsNamedMethodAndLocalVariableSuppressions() throws Exception {
        Compilation result = compile("""
                class Example {
                    @SuppressWarnings({"unused", "WrapperReferenceEquality"})
                    boolean fastPath(Entity first, Entity second) {
                        return first == second || first.equals(second);
                    }
                    boolean explicitlyLocal(Entity first, Entity second) {
                        @SuppressWarnings("WrapperReferenceEquality") boolean same = first == second;
                        return same || first.equals(second);
                    }
                }
                """);
        assertTrue(result.success(), result.errors().toString());
    }

    @Test
    void broadSuppressionsCannotHideUnsafeComparisons() throws Exception {
        Compilation result = compile("""
                @SuppressWarnings("WrapperReferenceEquality")
                class Example {
                    @SuppressWarnings("all")
                    boolean matches(Player first, Player second) { return first == second; }
                }
                """);
        assertRejected(result, 1);
    }

    @Test
    void reportsNestedClassesOnceWithoutSuppressingOtherMethods() throws Exception {
        Compilation result = compile("""
                class Example {
                    @SuppressWarnings("WrapperReferenceEquality")
                    boolean allowed(Player first, Player second) { return first == second; }
                    static class Nested {
                        boolean unsafe(Entity first, Entity second) { return first == second; }
                    }
                    boolean unsafe(Player first, Player second) { return first != second; }
                }
                class Another {
                    boolean unsafe(Block first, Block second) { return first == second; }
                }
                """);
        assertRejected(result, 3);
    }

    @Test
    void compilerCanDiscoverPluginThroughItsServiceRegistration() {
        assertTrue(ServiceLoader.load(com.sun.source.util.Plugin.class).stream()
                .anyMatch(provider -> provider.type() == WrapperReferenceEqualityPlugin.class));
    }

    private Compilation compile(String body) throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var manager = compiler.getStandardFileManager(diagnostics, null, null)) {
            List<JavaFileObject> sources = new ArrayList<>();
            sources.add(source("com.projectkorra.projectkorra.platform.mc.entity.Entity", """
                    package com.projectkorra.projectkorra.platform.mc.entity;
                    public class Entity { public Object handle() { return this; } }
                    """));
            sources.add(source("com.projectkorra.projectkorra.platform.mc.OfflinePlayer", """
                    package com.projectkorra.projectkorra.platform.mc;
                    public interface OfflinePlayer { }
                    """));
            sources.add(source("com.projectkorra.projectkorra.platform.mc.entity.Player", """
                    package com.projectkorra.projectkorra.platform.mc.entity;
                    public class Player extends Entity implements com.projectkorra.projectkorra.platform.mc.OfflinePlayer { }
                    """));
            sources.add(source("com.projectkorra.projectkorra.platform.mc.block.Block", """
                    package com.projectkorra.projectkorra.platform.mc.block;
                    public class Block { }
                    """));
            sources.add(source("Example", IMPORTS + body));
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    List.of("-proc:none", "--release", "21", "-d", output.toString()), null, sources);
            new WrapperReferenceEqualityPlugin().init(task);
            boolean success = task.call();
            return new Compilation(success, diagnostics.getDiagnostics().stream()
                    .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR).toList());
        }
    }

    private static void assertRejected(Compilation result, int count) {
        assertFalse(result.success(), "Compilation must fail on unsafe wrapper comparisons");
        assertEquals(count, result.errors().size(), result.errors().toString());
        assertTrue(result.errors().stream().allMatch(error -> error.getMessage(null)
                .startsWith("[WrapperReferenceEquality]")), result.errors().toString());
    }

    private static JavaFileObject source(String name, String code) {
        return new SimpleJavaFileObject(URI.create("string:///" + name.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
        };
    }

    private record Compilation(boolean success, List<Diagnostic<? extends JavaFileObject>> errors) { }
}

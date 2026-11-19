package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class PaperRollbackTerrainChunksTest {
    private static final Bounds ARENA = new Bounds(0, 0, 0, 16, 16, 16);

    @Test void overlappingLeasesShareLoadsAndReleaseOnlyTheirOwnTickets() {
        var source = new Source();
        source.tickets.add("0,0"); // A ticket from another part of the plugin.
        var first = source.chunks.prepare(source.world, ARENA);
        var second = source.chunks.prepare(source.world, ARENA);
        assertFalse(first.poll());
        assertFalse(second.poll());
        assertEquals(8, source.loads.size());
        for (int i = 0; i < 5; i++) {
            source.loads.values().forEach(future -> future.complete(source.chunk));
            first.poll();
            second.poll();
        }
        assertTrue(first.poll());
        assertTrue(second.poll());
        assertEquals(25, source.tickets.size());
        first.close();
        assertEquals(25, source.tickets.size());
        second.close();
        second.close();
        assertEquals(Set.of("0,0"), source.tickets);
    }

    @Test void failedLoadCanReleasePinsAndRetryTheSameArena() {
        var source = new Source();
        var failed = source.chunks.prepare(source.world, ARENA);
        assertFalse(failed.poll());
        source.loads.values().iterator().next().completeExceptionally(new IllegalStateException("chunk unavailable"));
        assertThrows(CompletionException.class, failed::poll);
        failed.close();
        source.loads.clear();
        var retry = source.chunks.prepare(source.world, ARENA);
        assertFalse(retry.poll());
        for (int i = 0; i < 5; i++) {
            source.loads.values().forEach(future -> future.complete(source.chunk));
            retry.poll();
        }
        assertTrue(retry.poll());
        retry.close();
        assertTrue(source.tickets.isEmpty());
    }

    private static final class Source {
        final Map<String, CompletableFuture<Chunk>> loads = new HashMap<>();
        final Set<String> tickets = new HashSet<>();
        final Chunk chunk = proxy(Chunk.class, (p, m, a) -> { throw new AssertionError(m); });
        final World world = proxy(World.class, (p, m, a) -> switch (m.getName()) {
            case "hashCode" -> System.identityHashCode(p);
            case "equals" -> p == a[0];
            case "getMinHeight" -> 0;
            case "getMaxHeight" -> 16;
            case "isChunkLoaded" -> true;
            case "getChunkAtAsync" -> loads.computeIfAbsent(a[0] + "," + a[1], key -> new CompletableFuture<>());
            case "addPluginChunkTicket" -> tickets.add(a[0] + "," + a[1]);
            case "removePluginChunkTicket" -> tickets.remove(a[0] + "," + a[1]);
            default -> throw new AssertionError(m);
        });
        final PaperRollbackTerrainChunks chunks = new PaperRollbackTerrainChunks(
                proxy(Plugin.class, (p, m, a) -> { throw new AssertionError(m); }));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}

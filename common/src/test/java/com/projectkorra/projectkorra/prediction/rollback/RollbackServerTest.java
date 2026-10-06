package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackServerTest {
    @Test void capturedMetadataAndOwnedServicesCannotEscapeToLiveServer() {
        var metadata = new RollbackServer.Metadata("Paper test", "1.21.11", "captured", true, 12);
        var plugins = new ArrayList<>(List.of("owned-plugin")); var blocks = new Blocks();
        var server = new RollbackServer(metadata, plugins, blocks); plugins.clear();
        assertEquals(metadata, RollbackServer.Metadata.capture(server));
        assertEquals(List.of("owned-plugin"), server.plugins());
        assertThrows(UnsupportedOperationException.class, () -> server.plugins().clear());
        assertThrows(UnsupportedOperationException.class, server::handle);
        assertThrows(IllegalArgumentException.class, () -> server.createBlockData("STONE"));
        BlockData first = server.createBlockData(Material.STONE), second = server.createBlockData(Material.STONE);
        assertNotSame(first, second); assertEquals(Material.STONE, second.getMaterial());
        var checkpoint = new RollbackStateGraph(value -> false, field -> true, 1000).capture(List.of(server), List.of());
        server.createBlockData(Material.STONE); assertEquals(3, blocks.calls); checkpoint.restore(); assertEquals(2, blocks.calls);
        var failure = assertThrows(CompletionException.class, () -> CompletableFuture.supplyAsync(server::viewDistance).join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }
    private static final class Blocks implements RollbackServer.Blocks<Integer> {
        int calls;
        @Override public BlockData create(Material value) { calls++; return new BlockData(value); }
        @Override public Integer captureRollbackState() { return calls; }
        @Override public void restoreRollbackState(Integer value) { calls = value; }
    }
}

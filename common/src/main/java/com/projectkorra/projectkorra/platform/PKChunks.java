package com.projectkorra.projectkorra.platform;

import java.util.concurrent.CompletableFuture;

/**
 * Chunk loading facade.
 */
public interface PKChunks {
    CompletableFuture<?> getChunkAtAsync(Object location);
}

package com.projectkorra.projectkorra.fabric.client.prediction.entity;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntPredicate;

/** Allocates client-only entity IDs without entering the server's positive ID space. */
public final class PredictedEntityIdAllocator {
    private final AtomicInteger next;

    public PredictedEntityIdAllocator() {
        this(-1);
    }

    PredictedEntityIdAllocator(final int first) {
        this.next = new AtomicInteger(first < 0 ? first : -1);
    }

    /**
     * Returns an unused negative ID. Minecraft constructs every entity with a
     * process-local positive ID, but {@code ClientWorld.addEntity} removes the
     * entity already occupying that ID before insertion. Predicted entities
     * must therefore be moved out of the authoritative server ID namespace
     * before they are added to the client world.
     */
    public int reserve(final IntPredicate occupied) {
        final IntPredicate collision = occupied == null ? ignored -> false : occupied;
        while (true) {
            final int candidate = this.next.getAndUpdate(
                    current -> current == Integer.MIN_VALUE ? -1 : current - 1);
            if (!collision.test(candidate)) return candidate;
        }
    }
}

package com.projectkorra.projectkorra.fabric.client.prediction.entity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PredictedEntityIdAllocatorTest {
    @Test
    void reservesUniqueIdsOutsideTheAuthoritativePositiveNamespace() {
        final PredictedEntityIdAllocator allocator = new PredictedEntityIdAllocator();
        final Set<Integer> ids = new HashSet<>();

        for (int index = 0; index < 100; index++) {
            final int id = allocator.reserve(ids::contains);
            assertTrue(id < 0);
            assertTrue(ids.add(id));
        }
    }

    @Test
    void skipsAnOccupiedClientOnlyId() {
        final PredictedEntityIdAllocator allocator = new PredictedEntityIdAllocator();
        assertEquals(-2, allocator.reserve(id -> id == -1));
        assertEquals(-3, allocator.reserve(id -> false));
    }

    @Test
    void wrapsWithinTheNegativeNamespace() {
        final PredictedEntityIdAllocator allocator =
                new PredictedEntityIdAllocator(Integer.MIN_VALUE);
        assertEquals(Integer.MIN_VALUE, allocator.reserve(id -> false));
        assertEquals(-1, allocator.reserve(id -> false));
    }
}

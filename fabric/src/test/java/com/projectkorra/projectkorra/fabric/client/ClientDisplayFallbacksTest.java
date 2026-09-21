package com.projectkorra.projectkorra.fabric.client;

import com.projectkorra.projectkorra.fabric.client.prediction.entity.ClientDisplayFallbacks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientDisplayFallbacksTest {
    private static final class Display {
        boolean removed;
    }

    @Test
    void serverRopeTakesOverWhenPredictionEndsAtTheGrab() {
        ClientDisplayFallbacks<Display> fallbacks = new ClientDisplayFallbacks<>(display -> !display.removed);
        Display rope = new Display();
        fallbacks.pair(50, rope);
        assertTrue(fallbacks.hide(50), "do not draw a duplicate beside the predicted rope");

        rope.removed = true;
        assertFalse(fallbacks.hide(50), "a disappeared local rope must expose the streamed server rope");
        assertTrue(fallbacks.isPaired(rope), "a delayed second spawn must not reuse the same segment");
    }

    @Test
    void delayedSpawnForAnAlreadyRemovedPredictionIsVisibleImmediately() {
        ClientDisplayFallbacks<Display> fallbacks = new ClientDisplayFallbacks<>(display -> !display.removed);
        Display rope = new Display();
        rope.removed = true;
        fallbacks.pair(50, rope);
        assertFalse(fallbacks.hide(50));
    }

    @Test
    void serverDestroyDoesNotDiscardALivePredictionOrHideReusedIds() {
        ClientDisplayFallbacks<Display> fallbacks = new ClientDisplayFallbacks<>(display -> !display.removed);
        Display rope = new Display();
        fallbacks.pair(50, rope);
        fallbacks.remove(50);
        assertFalse(rope.removed);
        assertFalse(fallbacks.hide(50));
        assertFalse(fallbacks.isPaired(rope));
    }

    @Test
    void unrelatedEntitiesAreVisibleAndDisconnectDropsAllPairs() {
        ClientDisplayFallbacks<Display> fallbacks = new ClientDisplayFallbacks<>(display -> !display.removed);
        fallbacks.pair(50, new Display());
        assertFalse(fallbacks.hide(51));
        fallbacks.clear();
        assertFalse(fallbacks.hide(50));
    }
}

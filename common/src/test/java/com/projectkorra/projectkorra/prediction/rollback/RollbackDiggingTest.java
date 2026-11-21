package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackDiggingTest {
    @Test void pendingBreakSurvivesConfirmedTransferAndRewindAndAnAbortedBreakDoesNotDestroyTerrain() {
        var digging = new RollbackDigging();
        var player = new UUID(0, 1);
        var at = new Position(1, 4, 8);
        var destroyed = new ArrayList<Position>();
        var blocks = new RollbackDigging.Blocks() {
            public boolean mayBreak(Position pos) { return at.equals(pos); }
            public boolean creative() { return false; }
            public void attack(Position pos) { assertEquals(at, pos); }
            public float delta(Position pos) { return .2F; }
            public void destroy(Position pos) { destroyed.add(pos); }
        };
        digging.action(player, 1, RollbackInputActions.Kind.START_DESTROY_BLOCK, at, blocks);
        digging.action(player, 2, RollbackInputActions.Kind.ABORT_DESTROY_BLOCK, at, blocks);
        digging.action(player, 9, RollbackInputActions.Kind.STOP_DESTROY_BLOCK, at, blocks);
        assertTrue(destroyed.isEmpty());
        digging.action(player, 10, RollbackInputActions.Kind.START_DESTROY_BLOCK, at, blocks);
        digging.action(player, 11, RollbackInputActions.Kind.STOP_DESTROY_BLOCK, at, blocks);
        var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(RollbackDigging.class, RollbackDigging.Mining.class), List.of(), List.of()),
                new RollbackGraphCodec.Limits(64, 256, 65536, 1024));
        var transferred = (RollbackDigging) codec.decode(codec.encode(List.of(digging))).getFirst();
        var before = new RollbackStateGraph(value -> false, field -> true, 256).capture(List.of(transferred), List.of());
        transferred.tick(player, 13, blocks);
        assertTrue(destroyed.isEmpty());
        transferred.tick(player, 14, blocks);
        transferred.tick(player, 15, blocks);
        assertEquals(List.of(at), destroyed);
        before.restore();
        transferred.tick(player, 14, blocks);
        assertEquals(List.of(at, at), destroyed);
    }
}

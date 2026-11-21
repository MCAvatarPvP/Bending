package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.firebending.FireBlast;
import org.junit.jupiter.api.Test;
import org.objenesis.ObjenesisStd;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class RollbackRandomTest {
    @Test void preservesExistingSeededAbilityRandomSequences() {
        Random reference = new Random(917);
        RollbackRandom rollback = new RollbackRandom(917);
        for (int step = 0; step < 1_000; step++) {
            assertEquals(reference.nextInt(), rollback.nextInt());
            assertEquals(reference.nextInt(37), rollback.nextInt(37));
            assertEquals(reference.nextDouble(), rollback.nextDouble());
            assertEquals(reference.nextFloat(), rollback.nextFloat());
            assertEquals(reference.nextGaussian(), rollback.nextGaussian());
        }
    }

    @Test void restoresStreamAndGaussianCacheThroughGenericRuntimeSnapshot() {
        RollbackRandom random = new RollbackRandom(91);
        random.nextGaussian();
        var graph = new RollbackStateGraph(ignored -> false, ignored -> true, 100);
        var snapshot = graph.capture(List.of(random), List.of());
        long integer = random.nextLong();
        double gaussian = random.nextGaussian();
        for (int step = 0; step < 100; step++) random.nextDouble();
        snapshot.restore();
        assertEquals(integer, random.nextLong());
        assertEquals(gaussian, random.nextGaussian());
    }

    @Test void fireBlastKeepsItsOwnRandomSequenceAndAliasesThroughWireAndLiveHandoff() {
        var codec = new RollbackGraphCodec(new RollbackGraphCodec.Catalog(List.of(FireBlast.class), List.of(), List.of()),
                new RollbackGraphCodec.Limits(1024, 4096, 65536, 1024));
        var allocator = new ObjenesisStd();
        var first = allocator.newInstance(FireBlast.class);
        var linked = allocator.newInstance(FireBlast.class);
        var second = allocator.newInstance(FireBlast.class);
        var random = new RollbackRandom(914);
        var other = new RollbackRandom(914);
        first.setRandom(random);
        linked.setRandom(random);
        second.setRandom(other);
        random.nextGaussian();
        other.nextGaussian();

        var imported = codec.decode(codec.encode(List.of(first, linked, second)));
        var handoff = codec.rebind(imported, ignored -> null);
        assertNotSame(random, ((FireBlast) imported.getFirst()).getRandom());
        assertNotSame(((FireBlast) imported.getFirst()).getRandom(), ((FireBlast) handoff.getFirst()).getRandom());
        for (var graph : List.of(imported, handoff)) {
            var restored = ((FireBlast) graph.get(0)).getRandom();
            var distinct = ((FireBlast) graph.get(2)).getRandom();
            assertSame(restored, ((FireBlast) graph.get(1)).getRandom());
            assertNotSame(restored, distinct);
            var reference = new Random(914);
            reference.nextGaussian();
            assertEquals(reference.nextGaussian(), restored.nextGaussian());
            assertEquals(reference.nextLong(), restored.nextLong());
            assertEquals(other.captureRollbackState(), ((RollbackRandom) distinct).captureRollbackState());
        }
        assertEquals(other.captureRollbackState(), random.captureRollbackState());
    }

    @Test void generatorsCreatedDuringReplayReceiveTheSameSeedsInTheSameStep() {
        long first, second;
        try (var ignored = RollbackClock.at(1_000, 25, 50_000_000)) {
            first = new RollbackRandom().nextLong();
            second = new RollbackRandom().nextLong();
        }
        assertNotEquals(first, second);
        try (var ignored = RollbackClock.at(1_000, 25, 50_000_000)) {
            assertEquals(first, new RollbackRandom().nextLong());
            assertEquals(second, new RollbackRandom().nextLong());
        }
        assertFalse(RollbackClock.active());
    }

    @Test void sharedChoicesAndGeneratedIdsReplayIndependentlyOfNativeMonotonicClockOrigin() {
        double fraction;
        int choice;
        java.util.UUID id;
        try (var ignored = RollbackClock.at(1_000, 99_123L, 25, 50_000_000L)) {
            fraction = RollbackRandom.fraction();
            choice = RollbackRandom.shared().nextInt(17);
            id = RollbackRandom.uuid();
            assertEquals(99_123L + 25 * 50_000_000L, RollbackClock.nanos());
            assertEquals(2_250L, RollbackClock.millis());
        }
        try (var ignored = RollbackClock.at(1_000, -8_003L, 25, 50_000_000L)) {
            assertEquals(fraction, RollbackRandom.fraction());
            assertEquals(choice, RollbackRandom.shared().nextInt(17));
            assertEquals(id, RollbackRandom.uuid());
            assertEquals(4, id.version());
            assertEquals(2, id.variant());
        }
    }
}

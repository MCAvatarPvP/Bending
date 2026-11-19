package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.block.Block;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RollbackArenaBlockIndicesTest {
    @Test void participantEffectsKeepBothOwnersAndRejectCrossDuelTargets() {
        var victim = new com.projectkorra.projectkorra.platform.mc.entity.Player() {
            @Override public java.util.UUID getUniqueId() { return new java.util.UUID(0, 1); }
        };
        var caster = new com.projectkorra.projectkorra.platform.mc.entity.Player() {
            @Override public java.util.UUID getUniqueId() { return new java.util.UUID(0, 2); }
        };
        var outsider = new com.projectkorra.projectkorra.platform.mc.entity.Player() {
            @Override public java.util.UUID getUniqueId() { return new java.util.UUID(0, 3); }
        };
        var roster = Set.of(victim.getUniqueId(), caster.getUniqueId());
        var live = RollbackParticipantAbilityIndices.current();
        live.flames.put(victim, caster);
        live.flameTimes.put(victim, 42L);
        live.weakness.put(outsider, caster);
        try {
            assertThrows(IllegalStateException.class, () -> RollbackArenaBlockIndices.capture(block -> false, roster));
            assertSame(caster, live.flames.get(victim));
            live.weakness.put(outsider, outsider);
            var seed = RollbackArenaBlockIndices.capture(block -> false, roster);
            seed.detach(block -> false, roster);
            assertFalse(live.flames.containsKey(victim));
            assertSame(outsider, live.weakness.get(outsider));
            seed.participants.flameTimes.put(victim, 43L);
            seed.merge(block -> false, roster);
            assertSame(caster, live.flames.get(victim));
            assertEquals(43L, live.flameTimes.get(victim));
            assertSame(outsider, live.weakness.get(outsider));
        } finally {
            live.flames.remove(victim);
            live.flameTimes.remove(victim);
            live.weakness.remove(outsider);
        }
    }

    @Test void smokeBlindnessOwnershipExpiresAtTheSameTickAsThePotion() throws Exception {
        try (var world = new com.projectkorra.projectkorra.support.AbilityWorld()) {
            var player = world.player(0, 1, 0);
            var smoke = new org.objenesis.ObjenesisStd().newInstance(com.projectkorra.projectkorra.chiblocking.Smokescreen.class);
            smoke.setDuration(12);
            var live = RollbackParticipantAbilityIndices.live();
            live.smokeEffects.put(player.getName(), smoke);
            live.smokeTimes.put(player.getName(), 1_000L);
            live.smokeNames.put(player.getName(), player.getUniqueId());
            try {
                try (var clock = RollbackClock.at(1_000, 239, 50_000_000)) {
                    com.projectkorra.projectkorra.chiblocking.Smokescreen.removeFromHashMap(player);
                    assertSame(smoke, live.smokeEffects.get(player.getName()));
                }
                try (var clock = RollbackClock.at(1_000, 240, 50_000_000)) {
                    com.projectkorra.projectkorra.chiblocking.Smokescreen.removeFromHashMap(player);
                    assertFalse(live.smokeTimes.containsKey(player.getName()));
                    assertFalse(live.smokeEffects.containsKey(player.getName()));
                    assertFalse(live.smokeNames.containsKey(player.getName()));
                }
            } finally {
                live.smokeTimes.remove(player.getName());
                live.smokeEffects.remove(player.getName());
                live.smokeNames.remove(player.getName());
            }
        }
    }

    @Test void chiDeadlinesDetachOnlyTheRosterAndCheckChangesInMutableBombQueues() throws Exception {
        try (var world = new com.projectkorra.projectkorra.support.AbilityWorld()) {
            var enrolled = world.player(0, 2, 0);
            var outside = world.player(1, 2, 0);
            var roster = Set.of(enrolled.getUniqueId());
            var live = RollbackParticipantAbilityIndices.live();
            var queue = new java.util.ArrayList<>(List.of(25L, 40L));
            live.chiBlocks.put(enrolled, 30L);
            live.chiBlocks.put(outside, 55L);
            live.bombCooldowns.put(enrolled, queue);
            live.bombThrows.put(enrolled, 45L);
            try {
                var stale = RollbackArenaBlockIndices.capture(block -> false, roster);
                queue.add(60L);
                assertThrows(IllegalStateException.class, () -> stale.detach(block -> false, roster));
                assertSame(queue, live.bombCooldowns.get(enrolled));
                var seed = RollbackArenaBlockIndices.capture(block -> false, roster);
                seed.detach(block -> false, roster);
                assertFalse(live.bombCooldowns.containsKey(enrolled));
                assertFalse(live.chiBlocks.containsKey(enrolled));
                assertEquals(55L, live.chiBlocks.get(outside));
                seed.participants.chiBlocks.remove(enrolled);
                seed.participants.bombCooldowns.get(enrolled).removeFirst();
                seed.participants.bombThrows.put(enrolled, 65L);
                var shift = RollbackTimeShift.between(35L, 1_035L);
                seed.rebase(shift);
                seed.rebase(shift); // A retried handoff uses the same detached values.
                seed.merge(block -> false, roster);
                assertFalse(live.chiBlocks.containsKey(enrolled));
                assertEquals(List.of(1_040L, 1_060L), live.bombCooldowns.get(enrolled));
                assertEquals(1_065L, live.bombThrows.get(enrolled));
                assertEquals(55L, live.chiBlocks.get(outside));
            } finally {
                live.chiBlocks.remove(enrolled);
                live.chiBlocks.remove(outside);
                live.bombCooldowns.remove(enrolled);
                live.bombThrows.remove(enrolled);
            }
        }
    }

    @Test void correctionAndHandoffRetainArenaOwnershipAndOutsideChanges() {
        var a = new Block();
        var b = new Block();
        var outside = new Block();
        var live = RollbackArenaBlockIndices.live();
        var arena = Set.of(a, b);
        live.spout.put(a, a);
        live.frozen.add(a);
        live.frozen.add(a);
        live.spearExpiry.put(outside, 21L);
        try {
            var seed = RollbackArenaBlockIndices.capture(arena::contains, Set.of());
            seed.detach(arena::contains, Set.of());
            assertFalse(live.spout.containsKey(a));
            assertFalse(live.frozen.contains(a));
            assertFalse(seed.spearExpiry.containsKey(outside));
            var catalog = new RollbackGraphCodec.Catalog(List.of(RollbackArenaBlockIndices.class, RollbackParticipantAbilityIndices.class, com.projectkorra.projectkorra.earthbending.ShockwaveExecutionPolicy.MovedColumns.class), List.of(),
                    List.of(new RollbackGraphCodec.Binding("a", Block.class, a), new RollbackGraphCodec.Binding("b", Block.class, b)));
            var codec = new RollbackGraphCodec(catalog, new RollbackGraphCodec.Limits(512, 4096, 65536, 1024));
            var confirmed = (RollbackArenaBlockIndices) codec.decode(codec.encode(List.of(seed))).getFirst();
            assertEquals(List.of(a, a), confirmed.frozen);
            confirmed.spout.clear();
            confirmed.spout.put(b, b);
            live.spearExpiry.put(outside, 42L);
            live.frozen.add(b);
            assertThrows(IllegalStateException.class, () -> confirmed.merge(arena::contains, Set.of()));
            assertFalse(live.spout.containsKey(b));
            live.frozen.remove(b);
            confirmed.merge(arena::contains, Set.of());
            assertTrue(live.spout.containsKey(b));
            assertFalse(live.spout.containsKey(a));
            assertEquals(List.of(a, a), live.frozen.stream().filter(arena::contains).toList());
            assertEquals(42L, live.spearExpiry.get(outside));
        } finally {
            live.spout.remove(a);
            live.spout.remove(b);
            live.frozen.removeIf(arena::contains);
            live.spearExpiry.remove(outside);
        }
    }
}

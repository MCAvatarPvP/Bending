package com.projectkorra.projectkorra.prediction.rollback;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RollbackWorldSettingsTest {
    @Test void portableSettingsDetachRulesAndPreserveAllPolicyAndPrimitiveValues() {
        var rules = new HashMap<String, RollbackWorldSettings.Rule>();
        rules.put("minecraft:fire_damage", new RollbackWorldSettings.Flag(false));
        rules.put("minecraft:random_tick_speed", new RollbackWorldSettings.IntegerRule(Integer.MIN_VALUE));
        var fixture = settings(rules);
        var legacy = new TreeMap<String, Byte>();
        legacy.put("minecraft:water[level=1]", (byte) 1);
        legacy.put("minecraft:water[level=11]", (byte) 11);
        var data = new RollbackWorldSettings(fixture.gameTime(), fixture.randomSeed(), fixture.soundSeed(),
                fixture.seaLevel(), fixture.difficulty(), fixture.policy(), fixture.rules(), legacy);
        legacy.clear(); rules.clear();
        assertEquals(data, RollbackWorldSettings.decode(data.encode()));
        assertEquals(2, data.rules().size()); assertThrows(UnsupportedOperationException.class, () -> data.rules().clear());
    }
    @Test void malformedVersionsEnumsFlagsKeysAndTruncationReject() {
        var bytes = settings(Map.of("minecraft:fire_damage", new RollbackWorldSettings.Flag(false))).encode();
        for (int length = 0; length < bytes.length; length++) {
            var truncated = Arrays.copyOf(bytes, length); assertThrows(IllegalArgumentException.class, () -> RollbackWorldSettings.decode(truncated));
        }
        for (int offset : new int[]{0, 32, 33, 34}) {
            var invalid = bytes.clone(); invalid[offset] = (byte) 255;
            assertThrows(IllegalArgumentException.class, () -> RollbackWorldSettings.decode(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> RollbackWorldSettings.decode(Arrays.copyOf(bytes, bytes.length + 1)));
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of("not an id", new RollbackWorldSettings.Flag(true))));
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> RollbackWorldSettings.decode(new byte[RollbackWorldSettings.MAXIMUM_BYTES + 1]));
    }
    @Test void physicalKitRulesTravelWithTheArenaAndPreserveTheOrdinaryBendingKit() {
        var base = settings(Map.of("test:rule", new RollbackWorldSettings.Flag(true)));
        var position = new com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Position(7, 83, -6);
        var custom = new RollbackDuelInteractions(true, true, java.util.Set.of("WHITE_WOOL"), java.util.Set.of(position));
        var restored = RollbackWorldSettings.decode(base.withInteractions(custom).encode()).interactions();
        assertEquals(custom, restored);
        assertEquals(RollbackDuelInteractions.Decision.ALLOW, restored.breakDecision(7, 83, -6, "STONE"));
        assertEquals(RollbackDuelInteractions.Decision.DENY, restored.breakDecision(7, 82, -6, "STONE"));
        assertEquals(RollbackDuelInteractions.Decision.ALLOW, restored.breakDecision(0, 0, 0, "WHITE_WOOL"));
        assertEquals(RollbackDuelInteractions.Decision.LIVE_MATCH, restored.breakDecision(0, 0, 0, "RED_BED"));
        assertTrue(restored.consumeDrop("GLASS_BOTTLE"));
        assertFalse(restored.consumeDrop("IRON_INGOT"));
        assertEquals(RollbackDuelInteractions.STANDARD, RollbackWorldSettings.decode(base.encode()).interactions());
    }

    @Test void oversizedAndInvalidPolicyRejectBeforeEncoding() {
        var rules = new HashMap<String, RollbackWorldSettings.Rule>();
        for (int i = 0; i <= RollbackWorldSettings.MAXIMUM_RULES; i++) rules.put("test:rule" + i, new RollbackWorldSettings.Flag(true));
        assertThrows(IllegalArgumentException.class, () -> settings(rules));
        assertThrows(IllegalArgumentException.class, () -> new RollbackWorldSettings.Policy(RollbackWorldSettings.Dimension.NORMAL,
                true, false, true, false, true, 8, Float.NaN, .2F, 6, 1, false, true, 4, -64, OptionalInt.empty()));
    }
    private static RollbackWorldSettings settings(Map<String, RollbackWorldSettings.Rule> rules) {
        return new RollbackWorldSettings(20, 57, 53, 63, RollbackWorldSettings.Difficulty.HARD,
                new RollbackWorldSettings.Policy(RollbackWorldSettings.Dimension.NETHER, true, true, false, true, false, 5,
                        .15F, .3F, 7, 3, true, false, 3.5F, -32.5, OptionalInt.of(125)), rules);
    }
}

package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RollbackItemRulesTest {
    @Test void duelRuleRoundtripPreservesItemOverridesAndMergePolicy() throws Exception {
        var items = new RollbackItemRules(1580, Map.of("minecraft:stone", 300, "minecraft:glass_bottle", 10), .75, true, true);
        var rules = RollbackDuelInteractions.STANDARD.withItems(items);
        var bytes = new ByteArrayOutputStream();
        rules.write(new DataOutputStream(bytes));
        var restored = RollbackDuelInteractions.read(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
        assertEquals(rules, restored);
        assertEquals(300, restored.items().despawn("minecraft:stone"));
        assertEquals(1580, restored.items().despawn("minecraft:diamond"));
    }
}

package com.projectkorra.projectkorra.platform.mc;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NamespacedKeyTest {
    @Test void detachedAttributeKeysResolveTheSameDefinition() {
        var live = new NamespacedKey("projectkorra", "AVATAR_STATE");
        var detached = NamespacedKey.fromString("projectkorra:avatar_state");
        assertEquals("modifier", Map.of(live, "modifier").get(detached));
        assertNotEquals(live, NamespacedKey.fromString("other:avatar_state"));
        assertNotEquals(live, NamespacedKey.fromString("projectkorra:day_factor"));
    }
}

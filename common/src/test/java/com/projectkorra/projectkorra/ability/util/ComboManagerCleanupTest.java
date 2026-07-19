package com.projectkorra.projectkorra.ability.util;

import com.projectkorra.projectkorra.util.ClickType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ComboManagerCleanupTest {

    @Test
    void periodicCleanupKeepsFreshComboInputsAndDefinitionEntries() {
        final long now = 100_000L;
        final long retentionMillis = ComboManager.getCleanupDelay() * 50L;
        final ComboManager.AbilityInformation stale = new ComboManager.AbilityInformation(
                "AirSwipe", ClickType.LEFT_CLICK, now - retentionMillis - 1L);
        final ComboManager.AbilityInformation fresh = new ComboManager.AbilityInformation(
                "AirBurst", ClickType.SHIFT_DOWN, now - 1L);
        final ComboManager.AbilityInformation definition = new ComboManager.AbilityInformation(
                "AirBlast", ClickType.SHIFT_UP);
        final List<ComboManager.AbilityInformation> history = new ArrayList<>(
                List.of(stale, fresh, definition));

        ComboManager.pruneExpired(history, now);

        assertEquals(List.of(fresh, definition), history,
                "the independently scheduled cleanup must not erase a combo currently being entered");
    }
}

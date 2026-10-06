package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AbilityLagCompensatorTest {
    @Test void retainsOnlyTheRewindWindowAfterOneHundredThousandTicks() throws Exception {
        AbilityLagCompensator compensator = new AbilityLagCompensator((player, snapshot) -> fail());
        World world = new World();
        for (int tick = 0; tick < 100_000; tick++) {
            compensator.addSnapshot(new Location(world, tick, 0, 0), 1);
            assertEquals(tick, compensator.getCompensatedSnapshot(0).getLocation().getX());
            assertEquals(Math.max(0, tick - 2), compensator.getCompensatedSnapshot(100).getLocation().getX());
            assertEquals(Math.max(0, tick - AbilityLagCompensator.MAX_REWIND_TICKS),
                    compensator.getCompensatedSnapshot(Integer.MAX_VALUE).getLocation().getX());
            compensator.update();
        }
        Object[] history = (Object[]) field("snapshots").get(compensator);
        assertEquals(AbilityLagCompensator.MAX_REWIND_TICKS + 1, history.length);
    }

    @Test void missingFrameDoesNotReplayThePreviousRingContents() {
        AbilityLagCompensator compensator = new AbilityLagCompensator((player, snapshot) -> fail());
        compensator.addSnapshot(new Location(new World(), 0, 0, 0), 1);
        for (int i = 0; i <= AbilityLagCompensator.MAX_REWIND_TICKS; i++) compensator.update();
        assertNull(compensator.getCompensatedSnapshot(0));
        assertNull(compensator.getCompensatedSnapshot(Integer.MAX_VALUE));
    }

    @Test void negativePingUsesCurrentFrameAndOfflinePlayersAreReleased() throws Exception {
        AbilityLagCompensator compensator = new AbilityLagCompensator((player, snapshot) -> fail());
        compensator.addSnapshot(new Location(new World(), 7, 0, 0), 1);
        assertEquals(7, compensator.getCompensatedSnapshot(-100).getLocation().getX());
        compensator.addPlayer(new Player() { @Override public boolean isOnline() { return false; } });
        compensator.update();
        assertTrue(((Set<?>) field("players").get(compensator)).isEmpty());
    }

    private static Field field(String name) throws Exception {
        Field field = AbilityLagCompensator.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}

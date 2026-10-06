package com.projectkorra.projectkorra.region;

import com.projectkorra.projectkorra.hooks.RegionProtectionHook;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RegionProtectionTest {
    private Map<String, RegionProtectionHook> previous;
    private final World world = new World() { @Override public String getName() { return "test-world"; } };
    private Location origin = new Location(world, 0, 64, 0);
    private final Player player = new Player() {
        private final UUID id = UUID.randomUUID();
        @Override public UUID getUniqueId() { return id; }
        @Override public Location getLocation() { return origin; }
    };

    @BeforeEach void setup() {
        previous = new LinkedHashMap<>(RegionProtection.getActiveProtections());
        RegionProtection.getActiveProtections().clear();
        RegionProtection.startCleanCacheTask(5000);
    }

    @AfterEach void cleanup() {
        RegionProtection.getActiveProtections().clear();
        RegionProtection.getActiveProtections().putAll(previous);
        RegionProtection.startCleanCacheTask(5000);
    }

    @Test void repeatedChecksHitCacheWithoutCreatingBlockWrappersAndMovementRechecksOrigin() {
        AtomicInteger calls = new AtomicInteger();
        RegionProtection.registerRegionProtection("test", (p, location, ability) -> {
            calls.incrementAndGet();
            return location.getBlockX() == 1;
        });
        Location target = new Location(world, 10, 64, 0) {
            @Override public Block getBlock() { throw new AssertionError("cache allocated a block wrapper"); }
        };
        for (int i = 0; i < 10_000; i++) assertFalse(RegionProtection.isRegionProtected(player, target));
        assertEquals(2, calls.get());
        origin = new Location(world, 1, 64, 0);
        assertTrue(RegionProtection.isRegionProtected(player, target));
        assertEquals(4, calls.get());
    }

    @Test void hookRegistrationRemovalAndPlayerInvalidationDiscardOldDecisions() {
        assertFalse(RegionProtection.isRegionProtected(player, origin));
        RegionProtection.registerRegionProtection("test", (p, location, ability) -> true);
        assertTrue(RegionProtection.isRegionProtected(player, origin));
        RegionProtection.unloadPlugin("test");
        assertFalse(RegionProtection.isRegionProtected(player, origin));
        RegionProtection.getActiveProtections().put("test", (p, location, ability) -> true);
        RegionProtection.clearCache(player);
        assertTrue(RegionProtection.isRegionProtected(player, origin));
    }

    @Test void worldlessLocationsStillRunProtectionHooksInsteadOfFailing() {
        RegionProtection.registerRegionProtection("test", (p, location, ability) -> true);
        assertTrue(RegionProtection.isRegionProtected(player, new Location()));
    }

    @Test void reconfigurationDisablesCachingWithoutStartingAnySchedulerTasks() {
        AtomicInteger calls = new AtomicInteger();
        RegionProtection.registerRegionProtection("test", (p, location, ability) -> {
            calls.incrementAndGet();
            return true;
        });
        RegionProtection.startCleanCacheTask(0);
        assertTrue(RegionProtection.isRegionProtected(player, origin));
        assertTrue(RegionProtection.isRegionProtected(player, origin));
        assertEquals(2, calls.get());
    }
}

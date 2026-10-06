package com.projectkorra.projectkorra.region;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.World;
import org.junit.jupiter.api.Test;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RegionProtectionCacheTest {
    private final UUID player = UUID.randomUUID();
    private final Clock clock = new Clock();
    private final RegionProtectionCache cache = new RegionProtectionCache(5000, clock, 3, 8);

    @Test void expiresEvenWhenTheCleanupSchedulerNeverRunsAndReadsDoNotExtendLifetime() {
        cache.put(player, key(0), null, true);
        clock.millis = 4999;
        assertEquals(Boolean.TRUE, cache.get(player, key(0), null));
        clock.millis = 5000;
        assertNull(cache.get(player, key(0), null));
    }

    @Test void boundsBothPlayerCountAndBlocksUnderSustainedChurn() throws Exception {
        for (int p = 0; p < 100; p++) {
            UUID id = new UUID(0, p);
            for (int block = 0; block < 1000; block++) cache.put(id, key(block), null, false);
        }
        Cache<?, ?> players = players(cache);
        assertTrue(players.size() <= 3);
        for (Object blocks : players.asMap().values()) assertTrue(((Cache<?, ?>) blocks).size() <= 8);
    }

    @Test void productionLimitsBoundOneHundredThousandDistinctBlockQueries() throws Exception {
        RegionProtectionCache production = new RegionProtectionCache(5000, clock,
                RegionProtectionCache.MAX_PLAYERS, RegionProtectionCache.MAX_BLOCKS_PER_PLAYER);
        for (int i = 0; i < 100_000; i++) production.put(player, key(i), null, false);
        assertTrue(((Cache<?, ?>) players(production).getIfPresent(player)).size()
                <= RegionProtectionCache.MAX_BLOCKS_PER_PLAYER);
        assertEquals(Boolean.FALSE, production.get(player, key(99_999), null));
    }

    @Test void playerInvalidationDoesNotDiscardOtherPlayers() {
        UUID other = UUID.randomUUID();
        cache.put(player, key(0), null, true);
        cache.put(other, key(0), null, false);
        cache.clear(player);
        assertNull(cache.get(player, key(0), null));
        assertEquals(Boolean.FALSE, cache.get(other, key(0), null));
        cache.clear();
        assertNull(cache.get(other, key(0), null));
    }

    @Test void zeroLifetimeDisablesCaching() {
        RegionProtectionCache disabled = new RegionProtectionCache(0);
        disabled.put(player, key(0), null, true);
        assertNull(disabled.get(player, key(0), null));
    }

    @Test void doesNotReuseAnotherAbilityOrNullAbilityDecision() {
        TestAbility first = new TestAbility();
        TestAbility second = new TestAbility();
        cache.put(player, key(0), first, true);
        assertEquals(Boolean.TRUE, cache.get(player, key(0), first));
        assertNull(cache.get(player, key(0), second));
        assertNull(cache.get(player, key(0), null));
        cache.put(player, key(0), null, false);
        assertNull(cache.get(player, key(0), first));
    }

    @Test void liveCacheDoesNotKeepAnAbandonedAbilityAlive() throws Exception {
        WeakReference<TestAbility> abandoned = populateAbandoned();
        for (int i = 0; i < 100 && abandoned.get() != null; i++) {
            System.gc();
            Thread.sleep(10);
        }
        assertNull(abandoned.get(), "cache retained an abandoned ability");
        assertNull(cache.get(player, key(0), null), "collected ability matched a null ability query");
        Reference.reachabilityFence(cache);
    }

    @Test void includesWorldAndPlayerOriginWithoutRetainingWorldObjects() {
        World world = new World() { @Override public String getName() { return "world"; } };
        World other = new World() { @Override public String getName() { return "other"; } };
        Location target = new Location(world, -0.1, 64, 5);
        Location origin = new Location(world, 1, 64, 1);
        var key = RegionProtectionCache.Key.of(target, origin);
        assertEquals(-1, key.target().x());
        assertNotEquals(key, RegionProtectionCache.Key.of(target, new Location(world, 2, 64, 1)));
        assertNotEquals(key, RegionProtectionCache.Key.of(new Location(other, -0.1, 64, 5), origin));
        assertNotEquals(RegionProtectionCache.Key.of(null, origin), RegionProtectionCache.Key.of(origin, origin));
    }

    private WeakReference<TestAbility> populateAbandoned() {
        TestAbility ability = new TestAbility();
        cache.put(player, key(0), ability, true);
        return new WeakReference<>(ability);
    }

    private static RegionProtectionCache.Key key(int x) {
        return new RegionProtectionCache.Key(new RegionProtectionCache.Position("world", x, 64, 0),
                new RegionProtectionCache.Position("world", 0, 64, 0));
    }

    private static Cache<?, ?> players(RegionProtectionCache cache) throws Exception {
        Field field = RegionProtectionCache.class.getDeclaredField("players");
        field.setAccessible(true);
        return (Cache<?, ?>) field.get(cache);
    }

    private static final class Clock extends Ticker {
        long millis;
        @Override public long read() { return TimeUnit.MILLISECONDS.toNanos(millis); }
    }

    private static final class TestAbility extends CoreAbility {
        TestAbility() { super(null); }
        @Override public void progress() { }
        @Override public boolean isSneakAbility() { return false; }
        @Override public boolean isHarmlessAbility() { return true; }
        @Override public boolean isIgniteAbility() { return false; }
        @Override public boolean isExplosiveAbility() { return false; }
        @Override public long getCooldown() { return 0; }
        @Override public String getName() { return "CacheTest"; }
        @Override public Element getElement() { return Element.EARTH; }
        @Override public Location getLocation() { return null; }
    }
}

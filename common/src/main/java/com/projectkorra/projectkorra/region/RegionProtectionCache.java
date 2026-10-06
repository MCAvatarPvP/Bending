package com.projectkorra.projectkorra.region;

import com.google.common.base.Ticker;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;

import java.lang.ref.WeakReference;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Bounded decisions without strong references to abilities, players, blocks or worlds. */
final class RegionProtectionCache {
    static final int MAX_PLAYERS = 128;
    static final int MAX_BLOCKS_PER_PLAYER = 2048;
    private final Cache<UUID, Cache<Key, Decision>> players;
    private final long lifetimeMillis;
    private final Ticker ticker;
    private final int maxBlocks;

    RegionProtectionCache(long lifetimeMillis) {
        this(lifetimeMillis, Ticker.systemTicker(), MAX_PLAYERS, MAX_BLOCKS_PER_PLAYER);
    }

    RegionProtectionCache(long lifetimeMillis, Ticker ticker, int maxPlayers, int maxBlocks) {
        this.lifetimeMillis = Math.max(0, lifetimeMillis);
        this.ticker = ticker;
        this.maxBlocks = maxBlocks;
        players = CacheBuilder.newBuilder().concurrencyLevel(1).maximumSize(maxPlayers)
                .expireAfterAccess(this.lifetimeMillis, TimeUnit.MILLISECONDS).ticker(ticker).build();
    }

    Boolean get(UUID player, Key key, CoreAbility ability) {
        final Cache<Key, Decision> blocks = players.getIfPresent(player);
        final Decision decision = blocks == null ? null : blocks.getIfPresent(key);
        return decision != null && decision.matches(ability) ? decision.protectedRegion : null;
    }

    void put(UUID player, Key key, CoreAbility ability, boolean protectedRegion) {
        if (lifetimeMillis == 0) return;
        Cache<Key, Decision> blocks = players.getIfPresent(player);
        if (blocks == null) {
            final Cache<Key, Decision> created = CacheBuilder.newBuilder().concurrencyLevel(1)
                    .maximumSize(maxBlocks).expireAfterWrite(lifetimeMillis, TimeUnit.MILLISECONDS)
                    .ticker(ticker).build();
            final Cache<Key, Decision> existing = players.asMap().putIfAbsent(player, created);
            blocks = existing == null ? created : existing;
        }
        blocks.put(key, new Decision(ability, protectedRegion));
    }

    void clear(UUID player) {
        players.invalidate(player);
    }

    void clear() {
        players.invalidateAll();
    }

    // Each entry uses immutable coordinates; creating a Bukkit block wrapper is unnecessary.
    // The origin matters because protection hooks also inspect the player's current location.
    record Position(String world, int x, int y, int z) {
        static Position of(Location location) {
            return new Position(location.getWorld().getName(), location.getBlockX(),
                    location.getBlockY(), location.getBlockZ());
        }
    }

    record Key(Position target, Position origin) {
        static Key of(Location target, Location origin) {
            final Position from = Position.of(origin);
            return new Key(target == null ? null : Position.of(target), from);
        }
    }

    private static final class Decision {
        private final WeakReference<CoreAbility> ability;
        private final boolean protectedRegion;

        private Decision(CoreAbility ability, boolean protectedRegion) {
            this.ability = ability == null ? null : new WeakReference<>(ability);
            this.protectedRegion = protectedRegion;
        }

        private boolean matches(CoreAbility requested) {
            // A collected ability must never match a request that has no ability.
            if (requested == null) return ability == null;
            final CoreAbility cached = ability == null ? null : ability.get();
            return cached != null && cached.equals(requested);
        }
    }
}

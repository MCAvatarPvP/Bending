package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Bounds;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightingBounds;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Keeps the arena lighting and biome halo resident from capture through live restoration. */
final class PaperRollbackTerrainChunks {
    private static final int IN_FLIGHT = 8;
    private record Position(World world, int x, int z) {}
    private final Plugin plugin;
    private final Map<Position, Pin> shared = new HashMap<>();
    PaperRollbackTerrainChunks(Plugin plugin) { this.plugin = Objects.requireNonNull(plugin); }

    private final class Pin {
        private final Position at;
        private CompletableFuture<Chunk> loading;
        private boolean ready, addedTicket;
        private int leases;
        Pin(Position at) { this.at = at; loading = load(); }
        private CompletableFuture<Chunk> load() { return at.world().getChunkAtAsync(at.x(), at.z(), true); }
        boolean poll() {
            if (ready) return true;
            if (!loading.isDone()) return false;
            if (loading.join() == null) throw new IllegalStateException("Arena chunk unavailable " + at);
            if (!at.world().isChunkLoaded(at.x(), at.z())) { loading = load(); return false; }
            addedTicket = at.world().addPluginChunkTicket(at.x(), at.z(), plugin);
            loading = null;
            ready = true;
            return true;
        }
        void release() {
            if (--leases == 0) {
                shared.remove(at, this);
                if (addedTicket) at.world().removePluginChunkTicket(at.x(), at.z(), plugin);
            }
        }
    }

    final class Lease implements AutoCloseable {
        private final ArrayDeque<Position> requested = new ArrayDeque<>();
        private final List<Pin> waiting = new ArrayList<>(), held = new ArrayList<>();
        private boolean closed;
        private Lease(World world, Bounds arena) {
            var halo = RollbackLightingBounds.capture(arena, world.getMinHeight(), world.getMaxHeight(),
                    RollbackDuelRuntime.TERRAIN.maximumCells());
            for (int z = (halo.minZ() >> 4) - 1; z <= ((halo.maxZ() - 1) >> 4) + 1; z++)
                for (int x = (halo.minX() >> 4) - 1; x <= ((halo.maxX() - 1) >> 4) + 1; x++)
                    requested.add(new Position(world, x, z));
        }
        boolean poll() {
            if (closed) throw new IllegalStateException("Arena lease is closed");
            waiting.removeIf(Pin::poll);
            while (waiting.size() < IN_FLIGHT && !requested.isEmpty()) {
                var pin = shared.computeIfAbsent(requested.removeFirst(), Pin::new);
                pin.leases++;
                held.add(pin);
                waiting.add(pin);
            }
            return waiting.isEmpty() && requested.isEmpty();
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            held.forEach(Pin::release);
            held.clear(); waiting.clear(); requested.clear();
        }
    }
    Lease prepare(World world, Bounds arena) { return new Lease(world, arena); }
}

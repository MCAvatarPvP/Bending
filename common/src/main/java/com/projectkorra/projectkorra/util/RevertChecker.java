package com.projectkorra.projectkorra.util;

import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.configuration.PKConfiguration;
import com.projectkorra.projectkorra.platform.Platform;
import com.projectkorra.projectkorra.platform.mc.Server;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;


public class RevertChecker implements Runnable {

    private static final PKConfiguration config = ConfigManager.defaultConfig.get();
    private static final boolean safeRevert = config.getBoolean("Properties.Earth.SafeRevert");
    public static Map<Block, Block> earthRevertQueue = new ConcurrentHashMap<>();
    static Map<Integer, Integer> airRevertQueue = new ConcurrentHashMap<>();
    private final ProjectKorra plugin;
    private long time;
    private Future<Set<Map<String, Integer>>> returnFuture;

    public RevertChecker(final ProjectKorra bending) {
        this.plugin = bending;
    }

    public static void revertAirBlocks() {
        for (final int ID : airRevertQueue.keySet()) {
            Platform.chunks().getChunkAtAsync(EarthAbility.getTempAirLocations().get(ID).getState().getBlock().getLocation()).thenAccept(result -> EarthAbility.revertAirBlock(ID));
            RevertChecker.airRevertQueue.remove(ID);
        }
    }

    public static void revertEarthBlocks() {
        for (final Block block : earthRevertQueue.keySet()) {
            Platform.chunks().getChunkAtAsync(block.getLocation()).thenAccept(result -> EarthAbility.revertBlock(block));
            earthRevertQueue.remove(block);
        }
    }

    /** Drops queued old-world restores without touching either world. */
    public static void discardAll() {
        earthRevertQueue.clear();
        airRevertQueue.clear();
    }

    private void addToAirRevertQueue(final int i) {
        if (!airRevertQueue.containsKey(i)) {
            airRevertQueue.put(i, i);
        }
    }

    private void addToRevertQueue(final Block block) {
        if (!earthRevertQueue.containsKey(block)) {
            earthRevertQueue.put(block, block);
        }
    }

    @Override
    public void run() {
        if (!this.plugin.isEnabled()) {
            return;
        }

        this.time = System.currentTimeMillis();
        if (config.getBoolean("Properties.Earth.RevertEarthbending")) {

            try {
                this.returnFuture = this.plugin.getServer().getScheduler().callSyncMethod(this.plugin, new getOccupiedChunks(this.plugin.getServer()));
                final Set<Map<String, Integer>> chunks = this.returnFuture.get();

                final Map<Block, Information> earth = new HashMap<>(EarthAbility.getMovedEarth());

                for (final Block block : earth.keySet()) {
                    if (earthRevertQueue.containsKey(block)) {
                        continue;
                    }

                    final Information info = earth.get(block);

                    final Map<String, Integer> chunkcoord = new HashMap<>();
                    chunkcoord.put("x", block.getX() >> 4);
                    chunkcoord.put("z", block.getZ() >> 4);

                    if (this.time > (info.getTime() + config.getLong("Properties.Earth.RevertCheckTime")) && !(chunks.contains(chunkcoord) && safeRevert)) {
                        this.addToRevertQueue(block);
                    }
                }

                final Map<Integer, Information> air = new HashMap<>(EarthAbility.getTempAirLocations());

                for (final Integer i : air.keySet()) {
                    if (airRevertQueue.containsKey(i)) {
                        continue;
                    }

                    final Information info = air.get(i);
                    final Block block = info.getBlock();

                    final Map<String, Integer> chunkcoord = new HashMap<>();
                    chunkcoord.put("x", block.getX() >> 4);
                    chunkcoord.put("z", block.getZ() >> 4);

                    if (this.time > (info.getTime() + config.getLong("Properties.Earth.RevertCheckTime")) && !(chunks.contains(chunkcoord) && safeRevert)) {
                        this.addToAirRevertQueue(i);
                    }
                }
            } catch (final Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class getOccupiedChunks implements Callable<Set<Map<String, Integer>>> {
        private final Server server;

        public getOccupiedChunks(final Server server) {
            this.server = server;
        }

        @Override
        public Set<Map<String, Integer>> call() {
            final Set<Map<String, Integer>> chunks = new HashSet<>();

            for (final Player player : this.server.getOnlinePlayers()) {
                final Map<String, Integer> chunkcoord = new HashMap<>();
                chunkcoord.put("x", player.getLocation().getBlockX() >> 4);
                chunkcoord.put("z", player.getLocation().getBlockZ() >> 4);

                chunks.add(chunkcoord);
            }

            return chunks;
        }
    }
}

package com.projectkorra.projectkorra.prediction;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides physical server TempBlocks from the client that owns them.
 *
 * <p>Suppression is intentionally coordinate/lifecycle based. A single water
 * write can emit source, flowing-level and neighbor variants, none of which is
 * a reliable receipt for one logical TempBlock. The owner sees its local
 * client TempBlock; every physical server update at that coordinate is hidden
 * until the owned server layer closes. A short closing fence covers packets
 * queued by the restoring write itself.</p>
 */
public final class TempBlockPacketFilter extends PacketListenerAbstract {
    // PacketEvents observes the restoring write synchronously. Keep only a
    // two-tick scheduling cushion; a one-second coordinate fence could hide a
    // legitimate fluid/physics update and manufacture the very ghost it is
    // meant to prevent.
    private static final long CLOSING_FENCE_MILLIS = 100L;
    private final Map<UUID, Map<BlockKey, HideGate>> hidden = new ConcurrentHashMap<>();

    private TempBlockPacketFilter() {
    }

    public static TempBlockPacketFilter register() {
        final TempBlockPacketFilter filter = new TempBlockPacketFilter();
        PacketEvents.getAPI().getEventManager().registerListener(filter);
        return filter;
    }

    public void stop() {
        this.hidden.clear();
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }

    /** Starts a handshake with no gates inherited from an older connection. */
    public void resetViewer(final UUID viewer) {
        if (viewer != null) this.hidden.remove(viewer);
    }

    /** Arms an exact client that joined while this owned layer was active. */
    public void recordSnapshot(final com.projectkorra.projectkorra.platform.mc.block.Block block,
                               final UUID viewer, final long layerId, final BlockData viewerData) {
        if (block == null || viewer == null || block.getWorld() == null
                || !(block.getWorld().handle() instanceof org.bukkit.World world)
                || viewerData == null) return;
        this.hidden.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>())
                .compute(new BlockKey(world.getUID(), block.getX(), block.getY(), block.getZ()),
                        (ignored, previous) -> HideGate.withLayer(previous, layerId, viewerData));
    }

    /** Removes closing fences even when no later packet visits their coordinate. */
    public void pruneExpired() {
        final long now = System.currentTimeMillis();
        this.hidden.forEach((viewer, byBlock) -> {
            byBlock.entrySet().removeIf(entry -> !entry.getValue().active
                    && entry.getValue().untilMillis < now);
            if (byBlock.isEmpty()) this.hidden.remove(viewer, byBlock);
        });
    }

    /** Updates the fence before a world write and after metadata-only changes. */
    public void record(final TempBlockSync.Change change, final UUID predictedOwner,
                       final Map<UUID, BlockData> ownerViews) {
        if (change == null || change.block() == null || change.block().getWorld() == null
                || !(change.block().getWorld().handle() instanceof org.bukkit.World world)) return;

        final BlockKey key = new BlockKey(world.getUID(), change.block().getX(),
                change.block().getY(), change.block().getZ());
        final Set<UUID> viewers = new HashSet<>(ownerViews == null ? Set.of() : ownerViews.keySet());
        if (predictedOwner != null) viewers.add(predictedOwner);
        if (viewers.isEmpty()) return;

        final long now = System.currentTimeMillis();
        for (UUID viewer : viewers) {
            final BlockData viewerData = ownerViews == null ? null : ownerViews.get(viewer);
            final Map<BlockKey, HideGate> byBlock = this.hidden
                    .computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>());
            byBlock.compute(key, (ignored, previous) -> HideGate.advance(previous,
                    change.layerId(), viewer.equals(predictedOwner), change.operation(),
                    change.packetExpected(), now, viewerData));
            if (byBlock.isEmpty()) this.hidden.remove(viewer, byBlock);
        }
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !PaperPredictionServer.isExactClient(player.getUniqueId())) return;
        final UUID viewer = player.getUniqueId();
        final UUID worldId = player.getWorld().getUID();

        if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            final WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
            final var pos = packet.getBlockPosition();
            if (isHidden(viewer, new BlockKey(worldId, pos.getX(), pos.getY(), pos.getZ()))) {
                event.setCancelled(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            final WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(event);
            final List<WrapperPlayServerMultiBlockChange.EncodedBlock> visible = new ArrayList<>();
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : packet.getBlocks()) {
                if (!isHidden(viewer, new BlockKey(worldId, block.getX(), block.getY(), block.getZ()))) {
                    visible.add(block);
                }
            }
            if (visible.size() == packet.getBlocks().length) return;
            if (visible.isEmpty()) {
                event.setCancelled(true);
            } else {
                packet.setBlocks(visible.toArray(WrapperPlayServerMultiBlockChange.EncodedBlock[]::new));
                event.markForReEncode(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            final WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(event);
            final Column column = packet.getColumn();
            final BaseChunk[] sections = column.getChunks();
            boolean changed = false;
            final Map<BlockKey, HideGate> byBlock = this.hidden.get(viewer);
            if (byBlock == null) return;
            for (Map.Entry<BlockKey, HideGate> entry : byBlock.entrySet()) {
                final BlockKey block = entry.getKey();
                final HideGate gate = entry.getValue();
                if (!block.world.equals(worldId) || block.x >> 4 != column.getX()
                        || block.z >> 4 != column.getZ() || !gate.active
                        || gate.viewerData == null) continue;
                final int relativeY = block.y - player.getWorld().getMinHeight();
                final int sectionIndex = relativeY >> 4;
                if (sectionIndex < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null) continue;
                final WrappedBlockState state = packetState(event, gate.viewerData);
                sections[sectionIndex].set(block.x & 15, relativeY & 15, block.z & 15, state);
                changed = true;
            }
            if (changed) event.markForReEncode(true);
        }
    }

    private boolean isHidden(final UUID viewer, final BlockKey key) {
        final Map<BlockKey, HideGate> byBlock = this.hidden.get(viewer);
        if (byBlock == null) return false;
        final HideGate gate = byBlock.get(key);
        if (gate == null) return false;
        if (gate.active || gate.untilMillis >= System.currentTimeMillis()) return true;
        byBlock.remove(key, gate);
        if (byBlock.isEmpty()) this.hidden.remove(viewer, byBlock);
        return false;
    }

    private static WrappedBlockState packetState(final PacketSendEvent event, final BlockData data) {
        final org.bukkit.block.data.BlockData nativeData = BukkitMC.blockDataHandle(data);
        return WrappedBlockState.getByString(event.getUser().getClientVersion(), nativeData.getAsString());
    }

    private record BlockKey(UUID world, int x, int y, int z) {
    }

    private record HideGate(Set<Long> layers, BlockData viewerData, boolean active, long untilMillis) {
        private static HideGate withLayer(final HideGate previous, final long layerId,
                                          final BlockData viewerData) {
            final Set<Long> layers = previous == null ? new HashSet<>() : new HashSet<>(previous.layers);
            layers.add(layerId);
            return new HideGate(Set.copyOf(layers), viewerData.clone(), true, Long.MAX_VALUE);
        }

        private static HideGate advance(final HideGate previous, final long layerId,
                                        final boolean ownsOperation,
                                        final TempBlockSync.Operation operation,
                                        final boolean packetExpected, final long now,
                                        final BlockData viewerData) {
            final Set<Long> layers = previous == null ? new HashSet<>() : new HashSet<>(previous.layers);
            final boolean closes = operation == TempBlockSync.Operation.REVERT
                    || operation == TempBlockSync.Operation.DISCARD;
            if (ownsOperation) {
                if (closes) layers.remove(layerId);
                else layers.add(layerId);
            }
            final BlockData visible = viewerData != null ? viewerData.clone()
                    : previous == null || previous.viewerData == null ? null : previous.viewerData.clone();
            if (!layers.isEmpty()) {
                return new HideGate(Set.copyOf(layers), visible, true, Long.MAX_VALUE);
            }
            if (ownsOperation && closes && packetExpected) {
                return new HideGate(Set.of(), visible, false, now + CLOSING_FENCE_MILLIS);
            }
            return null;
        }
    }
}

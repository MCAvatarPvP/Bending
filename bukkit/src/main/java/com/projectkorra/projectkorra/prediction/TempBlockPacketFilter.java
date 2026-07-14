package com.projectkorra.projectkorra.prediction;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.enums.East;
import com.github.retrooper.packetevents.protocol.world.states.enums.North;
import com.github.retrooper.packetevents.protocol.world.states.enums.South;
import com.github.retrooper.packetevents.protocol.world.states.enums.West;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import com.github.retrooper.packetevents.protocol.world.states.type.StateValue;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerBlockChange;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMultiBlockChange;
import com.projectkorra.projectkorra.platform.bukkit.BukkitMC;
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.util.TempBlock;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Suppresses only server block packets proven to belong to an exact client's
 * owned TempBlock lifecycle. Each suppression consumes a pre-armed, bounded,
 * exact-state receipt. Unrelated authority at the same coordinate and unrelated
 * entries in a multi-block packet always pass unchanged.
 */
public final class TempBlockPacketFilter extends PacketListenerAbstract {
    private static final long RECEIPT_LIFETIME_MILLIS = 3_000L;
    private final Map<UUID, Map<BlockKey, ConcurrentLinkedDeque<Receipt>>> receipts = new ConcurrentHashMap<>();

    private TempBlockPacketFilter() {
    }

    public static TempBlockPacketFilter register() {
        TempBlockPacketFilter filter = new TempBlockPacketFilter();
        PacketEvents.getAPI().getEventManager().registerListener(filter);
        return filter;
    }

    public void stop() {
        receipts.clear();
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }

    public void record(final TempBlockSync.Change change) {
        if (change == null || change.block() == null || change.block().getWorld() == null
                || !change.packetExpected()
                || change.operation() == TempBlockSync.Operation.UPDATE_EXPIRY
                || !(change.block().getWorld().handle() instanceof org.bukkit.World world)) return;
        Set<UUID> viewers = new HashSet<>(change.ownerViews().keySet());
        if (change.ownerId() != null) viewers.add(change.ownerId());
        if (viewers.isEmpty()) return;
        BlockKey key = new BlockKey(world.getUID(), change.block().getX(), change.block().getY(), change.block().getZ());
        String physicalState = TempBlockSync.encode(change.data());
        long expiresAt = System.currentTimeMillis() + RECEIPT_LIFETIME_MILLIS;
        for (UUID viewer : viewers) {
            receipts.computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>())
                    .computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>())
                    .addLast(new Receipt(physicalState, expiresAt));
        }
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !PaperPredictionServer.isExactClient(player.getUniqueId())) return;
        UUID viewer = player.getUniqueId();
        UUID worldId = player.getWorld().getUID();

        if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
            var pos = packet.getBlockPosition();
            Receipt receipt = consume(viewer, new BlockKey(worldId, pos.getX(), pos.getY(), pos.getZ()),
                    packet.getBlockState(), event.getUser().getClientVersion());
            if (receipt != null) event.setCancelled(true);
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(event);
            List<WrapperPlayServerMultiBlockChange.EncodedBlock> visible = new ArrayList<>();
            for (WrapperPlayServerMultiBlockChange.EncodedBlock block : packet.getBlocks()) {
                Receipt receipt = consume(viewer, new BlockKey(worldId, block.getX(), block.getY(), block.getZ()),
                        block.getBlockState(event.getUser().getClientVersion()), event.getUser().getClientVersion());
                if (receipt == null) visible.add(block);
            }
            if (visible.size() == packet.getBlocks().length) return;
            if (visible.isEmpty()) event.setCancelled(true);
            else {
                packet.setBlocks(visible.toArray(WrapperPlayServerMultiBlockChange.EncodedBlock[]::new));
                event.markForReEncode(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(event);
            Column column = packet.getColumn();
            BaseChunk[] sections = column.getChunks();
            World commonWorld = BukkitMC.world(player.getWorld());
            boolean changed = false;
            for (TempBlock.VisibleBlock block : TempBlock.getOwnedBlocksInChunk(
                    commonWorld, column.getX(), column.getZ(), viewer)) {
                int relativeY = block.y() - player.getWorld().getMinHeight();
                int sectionIndex = relativeY >> 4;
                if (sectionIndex < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null
                        || block.data() == null) continue;
                WrappedBlockState state = packetState(event.getUser().getClientVersion(), block.data());
                sections[sectionIndex].set(block.x() & 15, relativeY & 15, block.z() & 15, state);
                changed = true;
            }
            if (changed) event.markForReEncode(true);
        }
    }

    private Receipt consume(final UUID viewer, final BlockKey key, final WrappedBlockState incoming,
                            final ClientVersion clientVersion) {
        Map<BlockKey, ConcurrentLinkedDeque<Receipt>> byBlock = receipts.get(viewer);
        if (byBlock == null) return null;
        ConcurrentLinkedDeque<Receipt> queue = byBlock.get(key);
        if (queue == null) return null;
        long now = System.currentTimeMillis();
        queue.removeIf(receipt -> receipt.expiresAt < now);
        Receipt matched = null;
        for (Receipt receipt : queue) {
            if (incoming != null && packetState(clientVersion, receipt.physicalState).equals(incoming)) {
                matched = receipt;
                break;
            }
        }
        if (matched != null) {
            Receipt consumed;
            do {
                consumed = queue.pollFirst();
            } while (consumed != null && consumed != matched);
            if (queue.isEmpty()) byBlock.remove(key, queue);
            if (byBlock.isEmpty()) receipts.remove(viewer, byBlock);
            return matched;
        }
        if (queue.isEmpty()) byBlock.remove(key, queue);
        return null;
    }

    private static WrappedBlockState packetState(final ClientVersion clientVersion, final BlockData data) {
        return packetState(clientVersion, TempBlockSync.encode(data));
    }

    private static WrappedBlockState packetState(final ClientVersion clientVersion, final String encoded) {
        String[] fields = encoded.split(";");
        String name = fields.length == 0 || fields[0].isBlank() ? "minecraft:air" : fields[0];
        StateType type = StateTypes.getByName(name);
        if (type == null && name.startsWith("minecraft:")) type = StateTypes.getByName(name.substring(10));
        if (type == null) return WrappedBlockState.getByString(clientVersion, "minecraft:air");
        WrappedBlockState state = WrappedBlockState.getDefaultState(clientVersion, type);
        for (int index = 1; index < fields.length; index++) {
            int separator = fields[index].indexOf('=');
            if (separator <= 0) continue;
            String property = fields[index].substring(0, separator);
            String value = fields[index].substring(separator + 1);
            try {
                if (property.equals("level") && state.hasProperty(StateValue.LEVEL)) {
                    state.setData(StateValue.LEVEL, Integer.parseInt(value));
                } else if (property.equals("waterlogged") && state.hasProperty(StateValue.WATERLOGGED)) {
                    state.setData(StateValue.WATERLOGGED, value.equals("1"));
                } else if (property.equals("faces")) {
                    Set<String> faces = Set.of(value.split(","));
                    if (state.hasProperty(StateValue.NORTH)) state.setData(StateValue.NORTH,
                            faces.contains("NORTH") ? North.TRUE : North.FALSE);
                    if (state.hasProperty(StateValue.EAST)) state.setData(StateValue.EAST,
                            faces.contains("EAST") ? East.TRUE : East.FALSE);
                    if (state.hasProperty(StateValue.SOUTH)) state.setData(StateValue.SOUTH,
                            faces.contains("SOUTH") ? South.TRUE : South.FALSE);
                    if (state.hasProperty(StateValue.WEST)) state.setData(StateValue.WEST,
                            faces.contains("WEST") ? West.TRUE : West.FALSE);
                    if (state.hasProperty(StateValue.UP)) state.setData(StateValue.UP, faces.contains("UP"));
                }
            } catch (IllegalArgumentException ignored) {
                return WrappedBlockState.getDefaultState(clientVersion, type);
            }
        }
        return state;
    }

    private record BlockKey(UUID world, int x, int y, int z) { }
    private record Receipt(String physicalState, long expiresAt) { }
}

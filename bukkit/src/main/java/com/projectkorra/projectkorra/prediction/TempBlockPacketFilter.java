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
import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.util.TempBlock;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.UUID;

/**
 * Removes server-owned copies of a prediction client's own TempBlock layers.
 * The local prediction remains the only producer of those visuals. Other
 * players continue receiving the normal authoritative world updates.
 */
public final class TempBlockPacketFilter extends PacketListenerAbstract {
    private TempBlockPacketFilter() {
    }

    public static TempBlockPacketFilter register() {
        final TempBlockPacketFilter filter = new TempBlockPacketFilter();
        PacketEvents.getAPI().getEventManager().registerListener(filter);
        return filter;
    }

    public void stop() {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (!(event.getPlayer() instanceof Player player)
                || !PaperPredictionServer.isExactClient(player.getUniqueId())) return;
        final UUID viewer = player.getUniqueId();
        final World world = BukkitMC.world(player.getWorld());

        if (event.getPacketType() == PacketType.Play.Server.BLOCK_CHANGE) {
            final WrapperPlayServerBlockChange packet = new WrapperPlayServerBlockChange(event);
            final var pos = packet.getBlockPosition();
            if (TempBlock.isTopLayerOwnedBy(world, pos.getX(), pos.getY(), pos.getZ(), viewer)) {
                event.setCancelled(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.MULTI_BLOCK_CHANGE) {
            final WrapperPlayServerMultiBlockChange packet = new WrapperPlayServerMultiBlockChange(event);
            final WrapperPlayServerMultiBlockChange.EncodedBlock[] original = packet.getBlocks();
            final WrapperPlayServerMultiBlockChange.EncodedBlock[] filtered = Arrays.stream(original)
                    .filter(block -> !TempBlock.isTopLayerOwnedBy(world, block.getX(), block.getY(), block.getZ(), viewer))
                    .toArray(WrapperPlayServerMultiBlockChange.EncodedBlock[]::new);
            if (filtered.length == original.length) return;
            if (filtered.length == 0) {
                event.setCancelled(true);
            } else {
                packet.setBlocks(filtered);
                event.markForReEncode(true);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.CHUNK_DATA) {
            final WrapperPlayServerChunkData packet = new WrapperPlayServerChunkData(event);
            final Column column = packet.getColumn();
            final BaseChunk[] sections = column.getChunks();
            boolean changed = false;
            for (TempBlock.VisibleBlock block : TempBlock.getOwnedBlocksInChunk(
                    world, column.getX(), column.getZ(), viewer)) {
                final int relativeY = block.y() - player.getWorld().getMinHeight();
                final int sectionIndex = relativeY >> 4;
                if (sectionIndex < 0 || sectionIndex >= sections.length || sections[sectionIndex] == null
                        || block.data() == null) continue;
                final WrappedBlockState state = WrappedBlockState.getByString(
                        event.getUser().getClientVersion(), block.data().getAsString());
                sections[sectionIndex].set(block.x() & 15, relativeY & 15, block.z() & 15, state);
                changed = true;
            }
            if (changed) event.markForReEncode(true);
        }
    }
}

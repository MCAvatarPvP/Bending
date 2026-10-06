package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import java.util.*;

/** Detached provisional native presentation; never delivers packets or retains mutable native stacks. */
public final class FabricRollbackPacketData {
    public record HeldSlot(UUID player, int slot) implements FabricRollbackWorldAccess.Output {
        public HeldSlot {
            Objects.requireNonNull(player);
            if (slot < 0 || slot > 8) throw new IllegalArgumentException("Selected slot outside hotbar");
        }
    }
    public record Tracked(UUID entity, boolean includeSelf, Data data) implements FabricRollbackWorldAccess.Output { }
    public sealed interface Data permits Equipment, Status, Animation { int entityId(); }
    public record Equipped(String slot, RollbackItemData item) { }
    public record Equipment(int entityId, List<Equipped> slots) implements Data {
        public Equipment { slots = List.copyOf(slots); }
    }
    public record Status(int entityId, byte status) implements Data { }
    public record Animation(int entityId, int animation) implements Data { }
    private final FabricRollbackItemCodec items;
    FabricRollbackPacketData(RegistryWrapper.WrapperLookup registries) { items = new FabricRollbackItemCodec(registries); }

    /** Unknown packet families require an explicit adapter; they cannot fall through to a connection. */
    Data capture(Packet<?> packet) {
        if (packet instanceof EntityEquipmentUpdateS2CPacket equipment) {
            if (equipment.getEquipmentList().size() > 64) throw new IllegalStateException("Equipment packet slot budget");
            int bytes = 64; var slots = new ArrayList<Equipped>(); var seen = new HashSet<String>();
            for (var pair : equipment.getEquipmentList()) {
                String slot = pair.getFirst().asString();
                if (!seen.add(slot)) throw new IllegalArgumentException("Duplicate equipment packet slot");
                var item = items.encode(pair.getSecond());
                if (item.size() > 1_048_576 - bytes - 32) throw new IllegalStateException("Detached packet exceeds rollback budget");
                bytes += item.size() + 32; slots.add(new Equipped(slot, item));
            }
            return new Equipment(equipment.getEntityId(), slots);
        }
        if (packet instanceof EntityStatusS2CPacket status) {
            var buffer = new PacketByteBuf(Unpooled.buffer(5, 5));
            try { EntityStatusS2CPacket.CODEC.encode(buffer, status); return new Status(buffer.readInt(), buffer.readByte()); }
            finally { buffer.release(); }
        }
        if (packet instanceof EntityAnimationS2CPacket animation) return new Animation(animation.getEntityId(), animation.getAnimationId());
        return null;
    }
}

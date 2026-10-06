package com.projectkorra.projectkorra.prediction.rollback;

import com.google.gson.JsonElement;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackItemData;
import net.minecraft.advancements.Advancement;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Server-internal packet journal for data whose wire codecs reach live item
 * sanitizers. These records are not client wire messages. Final delivery must
 * rebuild native packets and apply Paper's normal audience/sanitization rules.
 */
public final class PaperRollbackPacketData {
    public sealed interface Output extends PaperRollbackCombatAccess.Output permits Direct, Tracked { }
    public record Direct(UUID player, Data data) implements Output { }
    public record Tracked(UUID entity, boolean includeSelf, Data data) implements Output { }
    public sealed interface Data permits Slot, HeldSlot, Content, Cursor, InventorySlot, Equipment, EntityStatus, Animation, Advancements { }
    public record HeldSlot(int slot) implements Data {
        public HeldSlot { if (slot < 0 || slot > 8) throw new IllegalArgumentException("Selected slot outside hotbar"); }
    }
    public record Slot(int container, int revision, int slot, RollbackItemData item) implements Data { }
    public record Content(int container, int revision, List<RollbackItemData> items, RollbackItemData carried) implements Data {
        public Content { items = List.copyOf(items); }
    }
    public record Cursor(RollbackItemData item) implements Data { }
    public record InventorySlot(int slot, RollbackItemData item) implements Data { }
    public record Equipped(String slot, RollbackItemData item) { }
    public record Equipment(int entity, List<Equipped> slots, boolean sanitize) implements Data {
        public Equipment { slots = List.copyOf(slots); }
    }
    public record EntityStatus(int entity, byte status) implements Data { }
    public record Animation(int entity, int animation) implements Data { }
    public record Definition(String id, String json, float x, float y) { }
    public record Criterion(String name, boolean complete, long obtainedMillis) { }
    public record Advancements(boolean reset, List<Definition> added, List<String> removed,
                               Map<String, List<Criterion>> progress, boolean show) implements Data {
        public Advancements {
            added = List.copyOf(added); removed = List.copyOf(removed);
            var detached = new TreeMap<String, List<Criterion>>();
            progress.forEach((id, criteria) -> detached.put(id, List.copyOf(criteria)));
            progress = Collections.unmodifiableMap(detached);
        }
    }

    private final PaperRollbackItemCodec items;
    private final DynamicOps<JsonElement> ops;

    PaperRollbackPacketData(RegistryAccess registries) {
        items = new PaperRollbackItemCodec(registries);
        ops = registries.createSerializationContext(JsonOps.INSTANCE);
    }

    /** Null means this packet must use another explicitly audited route. */
    Data capture(Packet<?> packet) {
        var budget = new Budget();
        if (packet instanceof ClientboundSetHeldSlotPacket value) return new HeldSlot(value.slot());
        if (packet instanceof ClientboundContainerSetSlotPacket value) {
            return new Slot(value.getContainerId(), value.getStateId(), value.getSlot(), item(value.getItem(), budget));
        }
        if (packet instanceof ClientboundContainerSetContentPacket value) {
            return new Content(value.containerId(), value.stateId(), value.items().stream().map(stack -> item(stack, budget)).toList(), item(value.carriedItem(), budget));
        }
        if (packet instanceof ClientboundSetCursorItemPacket value) return new Cursor(item(value.contents(), budget));
        if (packet instanceof ClientboundSetPlayerInventoryPacket value) return new InventorySlot(value.slot(), item(value.contents(), budget));
        if (packet instanceof ClientboundSetEquipmentPacket value) {
            return new Equipment(value.getEntity(), value.getSlots().stream()
                    .map(slot -> new Equipped(slot.getFirst().getSerializedName(), item(slot.getSecond(), budget))).toList(),
                    PaperRollbackPrivateAccess.equipmentSanitized(value));
        }
        if (packet instanceof ClientboundAnimatePacket value) return new Animation(value.getId(), value.getAction());
        if (packet instanceof ClientboundEntityEventPacket value) {
            // Its audited codec contains only the entity id and one status byte.
            var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer(5, 5));
            try {
                ClientboundEntityEventPacket.STREAM_CODEC.encode(buffer, value);
                return new EntityStatus(buffer.readInt(), buffer.readByte());
            } finally { buffer.release(); }
        }
        if (packet instanceof ClientboundUpdateAdvancementsPacket value) {
            var added = new ArrayList<Definition>();
            value.getAdded().stream().sorted(java.util.Comparator.comparing(holder -> holder.id().toString())).forEach(holder -> {
                String id = budget.text(holder.id().toString());
                String json = budget.text(Advancement.CODEC.encodeStart(ops, holder.value()).getOrThrow().toString());
                var display = holder.value().display();
                added.add(new Definition(id, json, display.map(info -> info.getX()).orElse(0F), display.map(info -> info.getY()).orElse(0F)));
            });
            var removed = value.getRemoved().stream().map(id -> budget.text(id.toString())).sorted().toList();
            var progress = new TreeMap<String, List<Criterion>>();
            value.getProgress().forEach((id, state) -> {
                var names = new TreeSet<String>();
                state.getCompletedCriteria().forEach(names::add); state.getRemainingCriteria().forEach(names::add);
                var criteria = names.stream().map(name -> {
                    var obtained = state.getCriterion(name).getObtained();
                    budget.add(16);
                    return new Criterion(budget.text(name), obtained != null, obtained == null ? 0 : obtained.toEpochMilli());
                }).toList();
                progress.put(budget.text(id.toString()), criteria);
            });
            return new Advancements(value.shouldReset(), added, removed, progress, value.shouldShowAdvancements());
        }
        return null;
    }

    private RollbackItemData item(ItemStack stack, Budget budget) {
        var data = items.encode(stack); budget.add(32 + data.size()); return data;
    }
    private static final class Budget {
        private int size = 64;
        void add(int amount) {
            if (amount < 0 || amount > 1_048_576 - size) throw new IllegalStateException("Detached packet exceeds rollback budget");
            size += amount;
        }
        String text(String text) { add(8 + text.getBytes(StandardCharsets.UTF_8).length); return text; }
    }
}

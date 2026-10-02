package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.fabric.mixin.client.AttributeContainerRollbackAccess;
import com.projectkorra.projectkorra.fabric.mixin.client.DataTrackerRollbackAccess;
import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;
import com.projectkorra.projectkorra.prediction.rollback.RollbackDomain;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerVitals;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

import java.io.*;
import java.util.*;

/** Same native vitals representation as Paper, applied only to an owned, unstarted player. */
public final class FabricRollbackPlayerVitals {
    private FabricRollbackPlayerVitals() { }

    public static RollbackPlayerVitals capture(FabricRollbackNativePlayerState state) {
        requireBootstrap(); return state.use(FabricRollbackPlayerVitals::capture);
    }
    private static RollbackPlayerVitals capture(PlayerEntity player) {
        byte[] tracked;
        var buffer = new RegistryByteBuf(Unpooled.buffer(256, RollbackPlayerVitals.MAXIMUM_BLOB), player.getEntityWorld().getRegistryManager());
        try {
            var entries = entries(player); buffer.writeVarInt(entries.length);
            for (var entry : entries) entry.toSerialized().write(buffer);
            tracked = new byte[buffer.readableBytes()]; buffer.readBytes(tracked);
        } finally { buffer.release(); }
        var active = player.getStatusEffects().stream()
                .sorted(Comparator.comparing(effect -> effect.getEffectType().getKey().orElseThrow().getValue().toString())).toList();
        if (active.size() > RollbackPlayerVitals.MAXIMUM_EFFECTS) throw new IllegalArgumentException("Player effect count");
        var tag = StatusEffectInstance.CODEC.listOf().encodeStart(player.getEntityWorld().getRegistryManager().getOps(NbtOps.INSTANCE), active)
                .getOrThrow(IllegalArgumentException::new);
        var effects = new RollbackPlayerVitals.Output(RollbackPlayerVitals.MAXIMUM_BLOB);
        try (var out = new DataOutputStream(effects)) { NbtIo.writeForPacket(tag, out); }
        catch (IOException failure) { throw new IllegalArgumentException("Player effects could not be encoded", failure); }
        var allocated = ((AttributeContainerRollbackAccess) player.getAttributes()).rollback$custom();
        if (allocated.size() > RollbackPlayerVitals.MAXIMUM_ATTRIBUTES) throw new IllegalArgumentException("Player attribute count");
        var attributes = new ArrayList<RollbackPlayerVitals.Attribute>();
        int totalModifiers = 0;
        for (var value : allocated.values()) {
            totalModifiers += value.getModifiers().size();
            if (totalModifiers > RollbackPlayerVitals.MAXIMUM_MODIFIERS) throw new IllegalArgumentException("Player modifier count");
            Set<Identifier> permanent = new HashSet<>();
            value.getPersistentModifiers().forEach(modifier -> permanent.add(modifier.id()));
            var modifiers = value.getModifiers().stream().map(modifier -> new RollbackPlayerVitals.Modifier(modifier.id().toString(), modifier.value(),
                    RollbackPlayerVitals.Operation.valueOf(modifier.operation().name()), permanent.contains(modifier.id()))).toList();
            attributes.add(new RollbackPlayerVitals.Attribute(value.getAttribute().getKey().orElseThrow().getValue().toString(), value.getBaseValue(), modifiers));
        }
        return new RollbackPlayerVitals(tracked, effects.toByteArray(), attributes);
    }

    public static void apply(FabricRollbackNativePlayerState state, RollbackPlayerVitals vitals) {
        requireBootstrap(); Objects.requireNonNull(vitals);
        state.use(player -> { apply(player, vitals); return null; });
    }
    private record NativeAttribute(RollbackPlayerVitals.Attribute value, RegistryEntry<EntityAttribute> type, List<EntityAttributeModifier> modifiers) { }

    private static void apply(PlayerEntity player, RollbackPlayerVitals vitals) {
        var expected = entries(player);
        var tracked = new ArrayList<DataTracker.SerializedEntry<?>>();
        var buffer = new RegistryByteBuf(Unpooled.wrappedBuffer(vitals.tracked()), player.getEntityWorld().getRegistryManager());
        try {
            if (buffer.readVarInt() != expected.length) throw new IllegalArgumentException("Player tracked-data count changed");
            for (var schema : expected) tracked.add(readTracked(buffer, schema));
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing player tracked data");
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid player tracked data", failure); } finally { buffer.release(); }
        List<StatusEffectInstance> effects;
        try (var in = new DataInputStream(new ByteArrayInputStream(vitals.effects()))) {
            var tag = NbtIo.read(in, new NbtSizeTracker(RollbackPlayerVitals.MAXIMUM_NBT_ALLOCATION, RollbackPlayerVitals.MAXIMUM_NBT_DEPTH));
            if (in.available() != 0) throw new IllegalArgumentException("Trailing player effect data");
            effects = StatusEffectInstance.CODEC.listOf().parse(player.getEntityWorld().getRegistryManager().getOps(NbtOps.INSTANCE), tag)
                    .getOrThrow(IllegalArgumentException::new);
        } catch (IOException | RuntimeException failure) { throw new IllegalArgumentException("Invalid player effect data", failure); }
        if (effects.size() > RollbackPlayerVitals.MAXIMUM_EFFECTS) throw new IllegalArgumentException("Player effect count");
        var effectTypes = new HashSet<>();
        for (var effect : effects) if (!effectTypes.add(effect.getEffectType())) throw new IllegalArgumentException("Duplicate player effect");
        var attributes = new ArrayList<NativeAttribute>();
        var registry = player.getEntityWorld().getRegistryManager().getOrThrow(RegistryKeys.ATTRIBUTE);
        for (var value : vitals.attributes()) {
            var type = registry.getEntry(Identifier.of(value.id())).orElseThrow(() -> new IllegalArgumentException("Unknown attribute " + value.id()));
            var modifiers = value.modifiers().stream().map(modifier -> new EntityAttributeModifier(Identifier.of(modifier.id()), modifier.amount(),
                    EntityAttributeModifier.Operation.valueOf(modifier.operation().name()))).toList();
            attributes.add(new NativeAttribute(value, type, modifiers));
        }
        // Do not call normal effect/pose hooks or reconcile attributes twice through effect addition.
        var target = player.getAttributes(); var access = (AttributeContainerRollbackAccess) target;
        access.rollback$custom().clear(); target.getTracked().clear(); target.getPendingUpdate().clear();
        for (var entry : attributes) {
            var instance = target.getCustomInstance(entry.type());
            if (instance == null) {
                // Paper permits registering a registry attribute outside the player's default supplier.
                instance = new EntityAttributeInstance(entry.type(), access::rollback$updateTrackedStatus);
                access.rollback$custom().put(entry.type(), instance);
            }
            instance.clearModifiers(); instance.setBaseValue(entry.value().base());
            for (int i = 0; i < entry.modifiers().size(); i++) {
                if (entry.value().modifiers().get(i).permanent()) instance.addPersistentModifier(entry.modifiers().get(i));
                else instance.addTemporaryModifier(entry.modifiers().get(i));
            }
        }
        player.getActiveStatusEffects().clear();
        for (var effect : effects) player.getActiveStatusEffects().put(effect.getEffectType(), effect);
        for (int i = 0; i < tracked.size(); i++) assign(expected[i], tracked.get(i));
    }
    private static <T> DataTracker.SerializedEntry<T> readTracked(RegistryByteBuf buffer, DataTracker.Entry<T> schema) {
        var data = schema.getData();
        if (buffer.readUnsignedByte() != data.id() || buffer.readVarInt() != TrackedDataHandlerRegistry.getId(data.dataType())) {
            throw new IllegalArgumentException("Player tracked-data schema changed");
        }
        return new DataTracker.SerializedEntry<>(data.id(), data.dataType(), data.dataType().codec().decode(buffer));
    }
    @SuppressWarnings("unchecked")
    private static <T> void assign(DataTracker.Entry<T> target, DataTracker.SerializedEntry<?> value) {
        target.set((T) value.value()); target.setDirty(true);
    }
    private static DataTracker.Entry<?>[] entries(PlayerEntity player) { return ((DataTrackerRollbackAccess) player.getDataTracker()).rollback$entries(); }
    private static void requireBootstrap() {
        if (RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Import/capture player vitals before replay");
    }
}

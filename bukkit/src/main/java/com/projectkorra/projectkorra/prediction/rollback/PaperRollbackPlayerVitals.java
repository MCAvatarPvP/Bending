package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.common.util.TickThread;
import io.netty.buffer.Unpooled;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.io.*;
import java.util.*;

/** Native serializers and attribute APIs for a private player's portable vitals component. */
public final class PaperRollbackPlayerVitals {
    private static final PaperRollbackPlayerFields.Field<Map> ATTRIBUTES =
            new PaperRollbackPlayerFields.Field<>(AttributeMap.class, "attributes", Map.class);
    private PaperRollbackPlayerVitals() { }

    static RollbackPlayerVitals capture(ServerPlayer player) {
        byte[] tracked;
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(256, RollbackPlayerVitals.MAXIMUM_BLOB), player.registryAccess());
        try {
            var entries = player.getEntityData().packAll(); buffer.writeVarInt(entries.size());
            for (var entry : entries) entry.write(buffer);
            tracked = new byte[buffer.readableBytes()]; buffer.readBytes(tracked);
        } finally { buffer.release(); }
        var active = player.getActiveEffects().stream()
                .sorted(Comparator.comparing(effect -> effect.getEffect().unwrapKey().orElseThrow().identifier().toString())).toList();
        if (active.size() > RollbackPlayerVitals.MAXIMUM_EFFECTS) throw new IllegalArgumentException("Player effect count");
        var tag = MobEffectInstance.CODEC.listOf().encodeStart(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), active)
                .getOrThrow(IllegalArgumentException::new);
        var effects = new RollbackPlayerVitals.Output(RollbackPlayerVitals.MAXIMUM_BLOB);
        try (var out = new DataOutputStream(effects)) { NbtIo.writeAnyTag(tag, out); }
        catch (IOException failure) { throw new IllegalArgumentException("Player effects could not be encoded", failure); }
        @SuppressWarnings("unchecked") Map<Holder<Attribute>, AttributeInstance> allocated = ATTRIBUTES.get(player.getAttributes());
        if (allocated.size() > RollbackPlayerVitals.MAXIMUM_ATTRIBUTES) throw new IllegalArgumentException("Player attribute count");
        var attributes = new ArrayList<RollbackPlayerVitals.Attribute>();
        int totalModifiers = 0;
        for (var value : allocated.values()) {
            totalModifiers += value.getModifiers().size();
            if (totalModifiers > RollbackPlayerVitals.MAXIMUM_MODIFIERS) throw new IllegalArgumentException("Player modifier count");
            Set<Identifier> permanent = new HashSet<>();
            value.getPermanentModifiers().forEach(modifier -> permanent.add(modifier.id()));
            var modifiers = value.getModifiers().stream().map(modifier -> new RollbackPlayerVitals.Modifier(modifier.id().toString(), modifier.amount(),
                    RollbackPlayerVitals.Operation.valueOf(modifier.operation().name()), permanent.contains(modifier.id()))).toList();
            attributes.add(new RollbackPlayerVitals.Attribute(value.getAttribute().unwrapKey().orElseThrow().identifier().toString(), value.getBaseValue(), modifiers));
        }
        return new RollbackPlayerVitals(tracked, effects.toByteArray(), attributes);
    }

    /** No decode failure can leave a partly modified native body. Values/clock/items/links remain separate components. */
    public static void apply(PaperRollbackNativePlayerState state, RollbackPlayerVitals vitals) {
        if (!TickThread.isTickThread() || RollbackClock.active() || RollbackDomain.active()) throw new IllegalStateException("Import vitals at the player bootstrap boundary");
        state.use(value -> {
            if (!(value instanceof ServerPlayer player)) throw new IllegalArgumentException("Vitals require a private server player");
            applyTo(player, vitals); return null;
        });
    }

    private record NativeAttribute(RollbackPlayerVitals.Attribute value, Holder<Attribute> type, List<AttributeModifier> modifiers) { }

    static void applyTo(ServerPlayer player, RollbackPlayerVitals vitals) {
        Objects.requireNonNull(vitals);
        var expected = player.getEntityData().packAll();
        var tracked = new ArrayList<SynchedEntityData.DataValue<?>>();
        var buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(vitals.tracked()), player.registryAccess());
        try {
            int count = buffer.readVarInt();
            if (count != expected.size()) throw new IllegalArgumentException("Player tracked-data count changed");
            for (var schema : expected) tracked.add(readTracked(buffer, schema));
            if (buffer.isReadable()) throw new IllegalArgumentException("Trailing player tracked data");
        } catch (RuntimeException failure) { throw new IllegalArgumentException("Invalid player tracked data", failure); } finally { buffer.release(); }
        List<MobEffectInstance> effects;
        try (var in = new DataInputStream(new ByteArrayInputStream(vitals.effects()))) {
            var tag = NbtIo.readAnyTag(in, new NbtAccounter(RollbackPlayerVitals.MAXIMUM_NBT_ALLOCATION, RollbackPlayerVitals.MAXIMUM_NBT_DEPTH));
            if (in.available() != 0) throw new IllegalArgumentException("Trailing player effect data");
            effects = MobEffectInstance.CODEC.listOf().parse(player.registryAccess().createSerializationContext(NbtOps.INSTANCE), tag)
                    .getOrThrow(IllegalArgumentException::new);
        } catch (IOException | RuntimeException failure) { throw new IllegalArgumentException("Invalid player effect data", failure); }
        if (effects.size() > RollbackPlayerVitals.MAXIMUM_EFFECTS) throw new IllegalArgumentException("Player effect count");
        var effectTypes = new HashSet<>();
        for (var effect : effects) if (!effectTypes.add(effect.getEffect())) throw new IllegalArgumentException("Duplicate player effect");
        var attributes = new ArrayList<NativeAttribute>();
        var registry = player.registryAccess().lookupOrThrow(Registries.ATTRIBUTE);
        for (var value : vitals.attributes()) {
            var type = registry.get(Identifier.parse(value.id())).orElseThrow(() -> new IllegalArgumentException("Unknown attribute " + value.id()));
            var modifiers = value.modifiers().stream().map(modifier -> new AttributeModifier(Identifier.parse(modifier.id()), modifier.amount(),
                    AttributeModifier.Operation.valueOf(modifier.operation().name()))).toList();
            attributes.add(new NativeAttribute(value, type, modifiers));
        }
        // Everything above is detached and validated. Below only owned native state is initialized.
        var target = player.getAttributes();
        ATTRIBUTES.get(target).clear(); target.getAttributesToSync().clear(); target.getAttributesToUpdate().clear();
        for (var entry : attributes) {
            if (!target.hasAttribute(entry.type())) target.registerAttribute(entry.type());
            var instance = target.getInstance(entry.type());
            instance.removeModifiers(); instance.setBaseValue(entry.value().base());
            for (int i = 0; i < entry.modifiers().size(); i++) {
                if (entry.value().modifiers().get(i).permanent()) instance.addPermanentModifier(entry.modifiers().get(i));
                else instance.addTransientModifier(entry.modifiers().get(i));
            }
        }
        player.getActiveEffectsMap().clear();
        for (var effect : effects) player.getActiveEffectsMap().put(effect.getEffect(), effect);
        for (var value : tracked) assign(player.getEntityData(), value);
    }

    private static <T> SynchedEntityData.DataValue<T> readTracked(RegistryFriendlyByteBuf buffer, SynchedEntityData.DataValue<T> schema) {
        if (buffer.readUnsignedByte() != schema.id() || buffer.readVarInt() != EntityDataSerializers.getSerializedId(schema.serializer())) {
            throw new IllegalArgumentException("Player tracked-data schema changed");
        }
        return new SynchedEntityData.DataValue<>(schema.id(), schema.serializer(), schema.serializer().codec().decode(buffer));
    }
    private static <T> void assign(SynchedEntityData target, SynchedEntityData.DataValue<T> value) {
        // Direct entry initialization avoids ordinary live pose/effect/plugin callbacks.
        var entry = target.getItem(new EntityDataAccessor<>(value.id(), value.serializer()));
        entry.setValue(value.value()); entry.setDirty(true);
    }
}

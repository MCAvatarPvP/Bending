package com.projectkorra.projectkorra.prediction;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Raw Bukkit plugin-message codec matching Fabric's RegistryByteBuf layout.
 */
final class PaperPredictionProtocol {
    static final int VERSION = 17;
    static final String HELLO = "projectkorra:client_hello";
    static final String INPUT = "projectkorra:input_frame";
    static final String PREPARE = "projectkorra:action_prepare";
    static final String HIT = "projectkorra:hit_claim";
    static final String HANDOFF = "projectkorra:authority_handoff";
    static final String SNAPSHOT = "projectkorra:server_snapshot";
    static final String STATE = "projectkorra:player_state";
    static final String CONFIG_CHUNK = "projectkorra:config_chunk";
    static final String RECONCILE = "projectkorra:reconcile";
    static final String TEMP_BLOCKS = "projectkorra:temp_blocks";
    static final String VELOCITY_OWNER = "projectkorra:velocity_owner";
    static final String VELOCITY_OWNER_V2 = "projectkorra:velocity_owner_v2";
    static final String ABILITY_REMOVED = "projectkorra:ability_removed";
    static final String STATE_DIRECTIVE = "projectkorra:state_directive";

    private PaperPredictionProtocol() {
    }

    static Hello readHello(byte[] data) {
        Reader reader = new Reader(data);
        Hello result = new Hello(reader.varInt(), reader.i64(), reader.varInt());
        reader.finished();
        return result;
    }

    static Input readInput(byte[] data) {
        Reader reader = new Reader(data);
        Input result = new Input(reader.uuid(), reader.varLong(), reader.i64(), reader.enumeration(InputKind.values()),
                reader.varInt(), reader.f32(), reader.f32(), reader.f64(), reader.f64(), reader.f64(),
                reader.bool(), reader.bool());
        reader.finished();
        return result;
    }

    static Prepare readPrepare(byte[] data) {
        Reader reader = new Reader(data);
        Prepare result = new Prepare(reader.uuid(), reader.varLong(), reader.i64(), reader.enumeration(InputKind.values()),
                reader.varInt(), reader.f32(), reader.f32(), reader.f64(), reader.f64(), reader.f64());
        reader.finished();
        return result;
    }

    static Hit readHit(byte[] data) {
        Reader reader = new Reader(data);
        Hit result = new Hit(reader.uuid(), reader.varLong(), reader.i64(), reader.varInt(),
                reader.f64(), reader.f64(), reader.f64());
        reader.finished();
        return result;
    }

    static Handoff readHandoff(byte[] data) {
        Reader reader = new Reader(data);
        Handoff result = new Handoff(reader.uuid(), reader.varLong());
        reader.finished();
        return result;
    }

    static byte[] snapshot(UUID session, long serverTick, long serverNowMillis, long epoch, int rewindTicks,
                           List<ConfigEntry> config, List<AbilityProfile> profiles,
                           Map<Integer, String> binds, Map<String, Long> cooldowns,
                           List<String> elements, List<String> subElements, double airBlastDecay) {
        Writer out = new Writer();
        out.varInt(VERSION).uuid(session).i64(serverTick).i64(serverNowMillis).i64(epoch).varInt(rewindTicks);
        out.varInt(config.size());
        for (ConfigEntry entry : config) writeConfigEntry(out, entry);
        out.varInt(profiles.size());
        for (AbilityProfile profile : profiles) {
            out.string(profile.name(), 128).string(profile.element(), 64).enumeration(profile.kind())
                    .f64(profile.speed()).f64(profile.range()).f64(profile.radius()).i64(profile.charge())
                    .i64(profile.cooldown()).string(profile.material(), 128).bool(profile.harmless()).bool(profile.sneak());
        }
        writeBinds(out, binds);
        writeCooldowns(out, cooldowns);
        writeStrings(out, elements);
        writeStrings(out, subElements);
        out.f64(airBlastDecay);
        return out.bytes();
    }

    static byte[] state(UUID session, long serverTick, long serverNowMillis, long acknowledgedSequence, Map<Integer, String> binds,
                        Map<String, Long> cooldowns, List<String> elements, List<String> subElements,
                        double airBlastDecay, List<String> activeFlightAbilities) {
        Writer out = new Writer();
        out.uuid(session).i64(serverTick).i64(serverNowMillis).varLong(acknowledgedSequence);
        writeBinds(out, binds);
        writeCooldowns(out, cooldowns);
        writeStrings(out, elements);
        writeStrings(out, subElements);
        out.f64(airBlastDecay);
        writeStrings(out, activeFlightAbilities);
        return out.bytes();
    }

    static byte[] configChunk(UUID session, long epoch, int index, int count, List<ConfigEntry> config) {
        Writer out = new Writer();
        out.uuid(session).i64(epoch).varInt(index).varInt(count).varInt(config.size());
        for (ConfigEntry entry : config) writeConfigEntry(out, entry);
        return out.bytes();
    }

    static int configEntrySize(ConfigEntry entry) {
        Writer out = new Writer();
        writeConfigEntry(out, entry);
        return out.bytes().length;
    }

    private static void writeConfigEntry(Writer out, ConfigEntry entry) {
        out.string(entry.path(), 512).enumeration(entry.type()).varInt(entry.values().size());
        for (String value : entry.values()) out.string(value, 8_192);
    }

    static byte[] reconcile(UUID session, long sequence, boolean accepted, String reason, long serverTick, long serverNowMillis,
                            String ability, double x, double y, double z, long cooldown) {
        return new Writer().uuid(session).varLong(sequence).bool(accepted).string(reason, 128).i64(serverTick)
                .i64(serverNowMillis).string(ability, 128).f64(x).f64(y).f64(z).i64(cooldown).bytes();
    }

    static byte[] tempBlocks(long serverTick, long serverNowMillis, List<TempBlockOp> operations) {
        Writer out = new Writer().i64(serverTick).i64(serverNowMillis).varInt(operations.size());
        for (TempBlockOp operation : operations) {
            out.enumeration(operation.operation()).string(operation.world(), 256)
                    .i32(operation.x()).i32(operation.y()).i32(operation.z())
                    .string(operation.material(), 128).i64(operation.revertAtMillis())
                    .varLong(operation.actionSequence()).varLong(operation.layerId())
                    .varLong(operation.revision()).bool(operation.ownerId() != null);
            if (operation.ownerId() != null) out.uuid(operation.ownerId());
            out.string(operation.viewerMaterial(), 128);
        }
        return out.bytes();
    }

    static byte[] velocityOwner(long serverTick, long actionSequence, int impulseOrdinal,
                                UUID abilityOwner, UUID target, String ability) {
        return new Writer().i64(serverTick).varLong(actionSequence).varInt(impulseOrdinal)
                .uuid(abilityOwner).uuid(target).string(ability, 128).bytes();
    }

    static byte[] velocityOwnerV2(long serverTick, long actionSequence, int impulseOrdinal,
                                  UUID abilityOwner, UUID target, int targetEntityId, String ability,
                                  double velocityX, double velocityY, double velocityZ) {
        return new Writer().i64(serverTick).varLong(actionSequence).varInt(impulseOrdinal)
                .uuid(abilityOwner).uuid(target).varInt(targetEntityId).string(ability, 128)
                .f64(velocityX).f64(velocityY).f64(velocityZ).bytes();
    }

    static byte[] abilityRemoved(UUID player, String ability, long actionSequence) {
        return new Writer().uuid(player).string(ability, 128).varLong(actionSequence).bytes();
    }

    static byte[] stateDirective(UUID session, String removedCooldown, String addedCooldown,
                                 long cooldownUntil, long serverNowMillis,
                                 boolean resetAirBlast, double airBlastDecay) {
        return new Writer().uuid(session).string(removedCooldown == null ? "" : removedCooldown, 128)
                .string(addedCooldown == null ? "" : addedCooldown, 128)
                .i64(cooldownUntil).i64(serverNowMillis).bool(resetAirBlast).f64(airBlastDecay).bytes();
    }

    private static void writeBinds(Writer out, Map<Integer, String> binds) {
        out.varInt(binds.size());
        binds.forEach((slot, ability) -> out.varInt(slot).string(ability, 128));
    }

    private static void writeCooldowns(Writer out, Map<String, Long> cooldowns) {
        out.varInt(cooldowns.size());
        cooldowns.forEach((ability, until) -> out.string(ability, 128).i64(until));
    }

    private static void writeStrings(Writer out, List<String> values) {
        out.varInt(values.size());
        for (String value : values) out.string(value, 128);
    }

    enum InputKind {LEFT_CLICK, RIGHT_CLICK, RIGHT_CLICK_BLOCK, RIGHT_CLICK_ENTITY, SNEAK_START, SNEAK_STOP}

    enum VisualKind {CAST, PROJECTILE, AREA, TEMP_BLOCK, SELF}

    enum ValueType {STRING, BOOLEAN, INTEGER, DECIMAL, STRING_LIST}

    enum TempOperation {CREATE, UPDATE_EXPIRY, REVERT}

    record ConfigEntry(String path, ValueType type, List<String> values) {
    }

    record AbilityProfile(String name, String element, VisualKind kind, double speed, double range, double radius,
                          long charge, long cooldown, String material, boolean harmless, boolean sneak) {
    }

    record Hello(int version, long clientTick, int capabilities) {
    }

    record Input(UUID session, long sequence, long clientTick, InputKind kind, int selectedSlot,
                 float yaw, float pitch, double x, double y, double z, boolean locallyPredicted,
                 boolean locallyBlockedByCooldown) {
    }

    record Prepare(UUID session, long sequence, long clientTick, InputKind kind, int selectedSlot,
                   float yaw, float pitch, double x, double y, double z) {
    }

    record Hit(UUID session, long sequence, long clientTick, int entityId, double x, double y, double z) {
    }

    record Handoff(UUID session, long sequence) {
    }

    record TempBlockOp(TempOperation operation, String world, int x, int y, int z, String material,
                       long revertAtMillis, long actionSequence, long layerId, long revision,
                       UUID ownerId, String viewerMaterial) {
    }

    static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Writer varInt(int value) {
            return varLong(value & 0xFFFFFFFFL);
        }

        Writer varLong(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int) (value & 0x7F) | 0x80);
                value >>>= 7;
            }
            out.write((int) value);
            return this;
        }

        Writer i64(long value) {
            for (int shift = 56; shift >= 0; shift -= 8) out.write((int) (value >>> shift));
            return this;
        }

        Writer i32(int value) {
            for (int shift = 24; shift >= 0; shift -= 8) out.write(value >>> shift);
            return this;
        }

        Writer uuid(UUID value) {
            return i64(value.getMostSignificantBits()).i64(value.getLeastSignificantBits());
        }

        Writer bool(boolean value) {
            out.write(value ? 1 : 0);
            return this;
        }

        Writer f32(float value) {
            return i32(Float.floatToRawIntBits(value));
        }

        Writer f64(double value) {
            return i64(Double.doubleToRawLongBits(value));
        }

        Writer enumeration(Enum<?> value) {
            return varInt(value.ordinal());
        }

        Writer string(String value, int maximumCharacters) {
            String safe = value == null ? "" : value;
            if (safe.length() > maximumCharacters) safe = safe.substring(0, maximumCharacters);
            byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
            varInt(bytes.length);
            out.writeBytes(bytes);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    static final class Reader {
        private final byte[] data;
        private int index;

        Reader(byte[] data) {
            this.data = data == null ? new byte[0] : data;
        }

        int varInt() {
            long value = varLong();
            if (value > Integer.MAX_VALUE) throw invalid("varint");
            return (int) value;
        }

        long varLong() {
            long result = 0;
            int shift = 0;
            while (shift < 64) {
                int current = u8();
                result |= (long) (current & 0x7F) << shift;
                if ((current & 0x80) == 0) return result;
                shift += 7;
            }
            throw invalid("varlong");
        }

        long i64() {
            long result = 0;
            for (int i = 0; i < 8; i++) result = (result << 8) | u8();
            return result;
        }

        int i32() {
            int result = 0;
            for (int i = 0; i < 4; i++) result = (result << 8) | u8();
            return result;
        }

        UUID uuid() {
            return new UUID(i64(), i64());
        }

        float f32() {
            return Float.intBitsToFloat(i32());
        }

        double f64() {
            return Double.longBitsToDouble(i64());
        }

        boolean bool() {
            int value = u8();
            if (value > 1) throw invalid("boolean");
            return value == 1;
        }

        <T> T enumeration(T[] values) {
            int ordinal = varInt();
            if (ordinal < 0 || ordinal >= values.length) throw invalid("enum");
            return values[ordinal];
        }

        String string(int maximumCharacters) {
            int size = varInt();
            if (size < 0 || size > maximumCharacters * 4 || index + size > data.length) throw invalid("string");
            String result = new String(data, index, size, StandardCharsets.UTF_8);
            index += size;
            if (result.length() > maximumCharacters) throw invalid("string length");
            return result;
        }

        int u8() {
            if (index >= data.length) throw invalid("end of packet");
            return data[index++] & 0xFF;
        }

        void finished() {
            if (index != data.length) throw invalid("trailing data");
        }

        private IllegalArgumentException invalid(String part) {
            return new IllegalArgumentException("Invalid prediction " + part);
        }
    }
}

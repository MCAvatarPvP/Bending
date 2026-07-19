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
    static final int VERSION = 43;
    static final int MAX_BLOCK_STATE_CHARACTERS = 512;
    static final String HELLO = "projectkorra:client_hello";
    static final String READY = "projectkorra:client_ready";
    static final String INPUT_VETO = "projectkorra:input_veto";
    static final String ACTION_TAG = "projectkorra:action_tag";
    static final String HIT_CLAIM = "projectkorra:hit_claim";
    static final String SNAPSHOT = "projectkorra:server_snapshot";
    static final String WORLD_STATE = "projectkorra:world_state";
    static final String NATIVE_ACTION = "projectkorra:native_action";
    static final String STATE = "projectkorra:player_state";
    static final String CONFIG_CHUNK = "projectkorra:config_chunk";
    static final String RECONCILE = "projectkorra:reconcile";
    static final String TEMP_BLOCKS = "projectkorra:temp_blocks";
    static final String VELOCITY_OWNER = "projectkorra:velocity_owner";
    static final String VELOCITY_OWNER_V2 = "projectkorra:velocity_owner_v2";
    static final String ABILITY_STATE_OWNER = "projectkorra:ability_state_owner";
    static final String TEMP_FALLING_BLOCK = "projectkorra:temp_falling_block";
    static final String TEMP_FALLING_BLOCK_PREPARE = "projectkorra:temp_falling_prepare";
    static final String DIRECT_BLOCK = "projectkorra:direct_block";
    static final String ABILITY_REMOVED = "projectkorra:ability_removed";
    static final String STATE_DIRECTIVE = "projectkorra:state_directive";
    static final String AIRBLAST_TRACE = "projectkorra:airblast_trace";

    private PaperPredictionProtocol() {
    }

    static Hello readHello(byte[] data) {
        Reader reader = new Reader(data);
        Hello result = new Hello(reader.varInt(), reader.i64(), reader.varInt());
        reader.finished();
        return result;
    }

    static Ready readReady(byte[] data) {
        Reader reader = new Reader(data);
        final UUID session = reader.uuid();
        final int count = reader.varInt();
        if (count < 0 || count > 2_048) throw new IllegalArgumentException("Invalid supported ability count");
        final java.util.ArrayList<String> abilities = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) abilities.add(reader.string(128));
        Ready result = new Ready(session, List.copyOf(abilities));
        reader.finished();
        return result;
    }

    static InputVeto readInputVeto(byte[] data) {
        Reader reader = new Reader(data);
        InputVeto result = new InputVeto(reader.uuid(), reader.varLong(),
                reader.enumeration(InputKind.values()), reader.string(128));
        reader.finished();
        return result;
    }

    static ActionTag readActionTag(byte[] data) {
        Reader reader = new Reader(data);
        ActionTag result = new ActionTag(reader.uuid(), reader.varLong(),
                reader.enumeration(InputKind.values()), reader.varInt(), reader.string(128));
        reader.finished();
        return result;
    }

    static HitClaim readHitClaim(byte[] data) {
        Reader reader = new Reader(data);
        HitClaim result = new HitClaim(reader.uuid(), reader.varLong(), reader.varLong(), reader.i64(),
                reader.uuid(), reader.varInt(), reader.string(128),
                reader.f64(), reader.f64(), reader.f64());
        reader.finished();
        return result;
    }

    static byte[] snapshot(UUID session, long serverTick, long serverNowMillis, long epoch, int rewindTicks,
                           List<ConfigEntry> config, List<AbilityProfile> profiles,
                           Map<Integer, String> binds, Map<String, Long> cooldowns,
                           List<String> elements, List<String> subElements,
                           List<String> permissions, double airBlastDecay, boolean chiBlocked,
                           RegionProtectionAuthority.Snapshot regionProtection) {
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
        writeStrings(out, permissions);
        out.f64(airBlastDecay).bool(chiBlocked);
        writeRegionProtection(out, regionProtection);
        return out.bytes();
    }

    static byte[] state(UUID session, long serverTick, long serverNowMillis, long acknowledgedSequence, Map<Integer, String> binds,
                        Map<String, Long> cooldowns, List<String> elements, List<String> subElements,
                        List<String> permissions, double airBlastDecay, boolean chiBlocked,
                        RegionProtectionAuthority.Snapshot regionProtection,
                        List<String> activeFlightAbilities) {
        Writer out = new Writer();
        out.uuid(session).i64(serverTick).i64(serverNowMillis).varLong(acknowledgedSequence);
        writeBinds(out, binds);
        writeCooldowns(out, cooldowns);
        writeStrings(out, elements);
        writeStrings(out, subElements);
        writeStrings(out, permissions);
        out.f64(airBlastDecay).bool(chiBlocked);
        writeRegionProtection(out, regionProtection);
        writeStrings(out, activeFlightAbilities);
        return out.bytes();
    }

    static byte[] worldState(UUID session, long worldGeneration, String worldIdentity) {
        return new Writer().uuid(session).varLong(worldGeneration).string(worldIdentity, 128).bytes();
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
                            String ability, double x, double y, double z, long cooldown,
                            boolean inputHandled, boolean comboRecorded, List<String> createdAbilities) {
        final Writer out = new Writer().uuid(session).varLong(sequence).bool(accepted).string(reason, 128).i64(serverTick)
                .i64(serverNowMillis).string(ability, 128).f64(x).f64(y).f64(z).i64(cooldown)
                .bool(inputHandled).bool(comboRecorded);
        writeStrings(out, createdAbilities);
        return out.bytes();
    }

    static byte[] nativeAction(UUID session, long sequence, long serverTick, InputKind kind,
                               int selectedSlot, String ability, double x, double y, double z,
                               float yaw, float pitch, boolean predictable) {
        return new Writer().uuid(session).varLong(sequence).i64(serverTick).enumeration(kind)
                .varInt(selectedSlot).string(ability == null ? "" : ability, 128)
                .f64(x).f64(y).f64(z).f32(yaw).f32(pitch).bool(predictable).bytes();
    }

    static byte[] tempBlocks(UUID session, long worldGeneration, String worldIdentity, boolean snapshot,
                             long serverTick, long serverNowMillis, List<TempBlockOp> operations) {
        Writer out = new Writer().uuid(session).varLong(worldGeneration).string(worldIdentity, 128)
                .bool(snapshot).i64(serverTick).i64(serverNowMillis).varInt(operations.size());
        for (TempBlockOp operation : operations) {
            out.enumeration(operation.operation()).string(operation.world(), 256)
                    .i32(operation.x()).i32(operation.y()).i32(operation.z())
                    .string(operation.material(), MAX_BLOCK_STATE_CHARACTERS).i64(operation.revertAtMillis())
                    .varLong(operation.actionSequence()).string(operation.effectAbility(), 128)
                    .i64(operation.effectStep()).varInt(operation.effectOrdinal()).varLong(operation.layerId())
                    .varLong(operation.revision()).bool(operation.ownerId() != null);
            if (operation.ownerId() != null) out.uuid(operation.ownerId());
            out.string(operation.viewerMaterial(), MAX_BLOCK_STATE_CHARACTERS).bool(operation.packetExpected());
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

    static byte[] abilityStateOwner(long serverTick, long actionSequence, int mutationOrdinal,
                                    UUID abilityOwner, UUID target, String ability,
                                    boolean flying, boolean allowFlight, float flySpeed) {
        return new Writer().i64(serverTick).varLong(actionSequence).varInt(mutationOrdinal)
                .uuid(abilityOwner).uuid(target).string(ability, 128)
                .bool(flying).bool(allowFlight).f32(flySpeed).bytes();
    }

    static byte[] tempFallingBlock(long serverTick, long actionSequence, int spawnOrdinal,
                                   UUID abilityOwner, int serverEntityId, String ability) {
        return new Writer().i64(serverTick).varLong(actionSequence).varInt(spawnOrdinal)
                .uuid(abilityOwner).varInt(serverEntityId).string(ability, 128).bytes();
    }

    static byte[] tempFallingBlockPrepare(long serverTick, long actionSequence, int spawnOrdinal,
                                          UUID abilityOwner, String ability, String world,
                                          double x, double y, double z, String material) {
        return new Writer().i64(serverTick).varLong(actionSequence).varInt(spawnOrdinal)
                .uuid(abilityOwner).string(ability, 128).string(world, 256)
                .f64(x).f64(y).f64(z).string(material, MAX_BLOCK_STATE_CHARACTERS).bytes();
    }

    static byte[] directBlock(long serverTick, long actionSequence, int mutationOrdinal,
                              UUID abilityOwner, String ability, String world,
                              int x, int y, int z, String material,
                              boolean movedEarthLifecycle) {
        return new Writer().i64(serverTick).varLong(actionSequence).varInt(mutationOrdinal)
                .uuid(abilityOwner).string(ability, 128).string(world, 256)
                .i32(x).i32(y).i32(z).string(material, MAX_BLOCK_STATE_CHARACTERS)
                .bool(movedEarthLifecycle).bytes();
    }

    static byte[] abilityRemoved(UUID player, String ability, String abilityType, long actionSequence,
                                 boolean externallyCaused, long acknowledgedSequence,
                                 int remainingTypeInstances) {
        return new Writer().uuid(player).string(ability, 128).string(abilityType, 256).varLong(actionSequence)
                .bool(externallyCaused).varLong(acknowledgedSequence).varInt(remainingTypeInstances).bytes();
    }

    static byte[] stateDirective(UUID session, String removedCooldown, String addedCooldown,
                                 long cooldownUntil, long serverNowMillis,
                                 boolean resetAirBlast, double airBlastDecay) {
        return new Writer().uuid(session).string(removedCooldown == null ? "" : removedCooldown, 128)
                .string(addedCooldown == null ? "" : addedCooldown, 128)
                .i64(cooldownUntil).i64(serverNowMillis).bool(resetAirBlast).f64(airBlastDecay).bytes();
    }

    static byte[] airBlastTrace(UUID session, long actionSequence, long serverTick,
                                AirBlastTraceSync.Trace trace) {
        return new Writer().uuid(session).varLong(actionSequence).i64(serverTick)
                .varInt(trace.eventOrdinal()).enumeration(trace.phase()).varInt(trace.progressTick())
                .f64(trace.eyeX()).f64(trace.eyeY()).f64(trace.eyeZ())
                .f32(trace.yaw()).f32(trace.pitch())
                .f64(trace.originX()).f64(trace.originY()).f64(trace.originZ())
                .f64(trace.targetX()).f64(trace.targetY()).f64(trace.targetZ())
                .f64(trace.locationX()).f64(trace.locationY()).f64(trace.locationZ())
                .f64(trace.directionX()).f64(trace.directionY()).f64(trace.directionZ())
                .f64(trace.speed()).f64(trace.range()).f64(trace.radius())
                .f64(trace.preShootStamina()).f64(trace.shotStamina())
                .i32(trace.blockX()).i32(trace.blockY()).i32(trace.blockZ())
                .string(trace.blockMaterial(), 128).bool(trace.removed()).bytes();
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

    private static void writeRegionProtection(final Writer out,
                                                final RegionProtectionAuthority.Snapshot snapshot) {
        final RegionProtectionAuthority.Snapshot safe = snapshot == null
                ? RegionProtectionAuthority.Snapshot.empty() : snapshot;
        out.string(safe.world(), 256);
        writeStrings(out, safe.abilities());
        out.varInt(safe.boxes().size());
        for (RegionProtectionAuthority.Box box : safe.boxes()) {
            out.i64(box.abilityMask()).i32(box.minX()).i32(box.minY()).i32(box.minZ())
                    .i32(box.maxX()).i32(box.maxY()).i32(box.maxZ());
        }
    }

    enum InputKind {LEFT_CLICK, RIGHT_CLICK, RIGHT_CLICK_BLOCK, RIGHT_CLICK_ENTITY, SNEAK_START, SNEAK_STOP, SWAP_HANDS}

    enum VisualKind {CAST, PROJECTILE, AREA, TEMP_BLOCK, SELF}

    enum ValueType {STRING, BOOLEAN, INTEGER, DECIMAL, STRING_LIST}

    enum TempOperation {CREATE, UPDATE_EXPIRY, REVERT, DISCARD}

    record ConfigEntry(String path, ValueType type, List<String> values) {
    }

    record AbilityProfile(String name, String element, VisualKind kind, double speed, double range, double radius,
                          long charge, long cooldown, String material, boolean harmless, boolean sneak) {
    }

    record Hello(int version, long clientTick, int capabilities) {
    }

    record Ready(UUID session, List<String> supportedAbilities) {
    }

    record InputVeto(UUID session, long sequence, InputKind kind, String ability) {
    }

    record ActionTag(UUID session, long clientSequence, InputKind kind, int selectedSlot,
                     String ability) {
    }

    record HitClaim(UUID session, long clientSequence, long serverSequence, long clientTick,
                    UUID target, int entityId, String ability, double x, double y, double z) {
    }

    record TempBlockOp(TempOperation operation, String world, int x, int y, int z, String material,
                       long revertAtMillis, long actionSequence, String effectAbility,
                       long effectStep, int effectOrdinal, long layerId, long revision,
                       UUID ownerId, String viewerMaterial, boolean packetExpected) {
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

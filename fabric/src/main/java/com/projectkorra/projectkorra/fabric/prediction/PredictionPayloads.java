package com.projectkorra.projectkorra.fabric.prediction;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Wire contract used by the Fabric client and the Paper/Fabric server endpoints. */
public final class PredictionPayloads {
    public static final int PROTOCOL_VERSION = 17;
    public static final int MAX_CONFIG_ENTRIES = 16_384;
    public static final int MAX_PROFILES = 2_048;
    public static final int MAX_TEMP_OPS = 4_096;
    private static boolean registered;

    private PredictionPayloads() { }

    public enum InputKind { LEFT_CLICK, RIGHT_CLICK, RIGHT_CLICK_BLOCK, RIGHT_CLICK_ENTITY, SNEAK_START, SNEAK_STOP }
    public enum VisualKind { CAST, PROJECTILE, AREA, TEMP_BLOCK, SELF }
    public enum ValueType { STRING, BOOLEAN, INTEGER, DECIMAL, STRING_LIST }
    public enum TempOperation { CREATE, UPDATE_EXPIRY, REVERT }

    public record ConfigEntry(String path, ValueType type, List<String> values) {
        static ConfigEntry read(RegistryByteBuf buf) {
            String path = buf.readString(512);
            ValueType type = buf.readEnumConstant(ValueType.class);
            int size = bounded(buf.readVarInt(), 256, "config value count");
            List<String> values = new ArrayList<>(size);
            for (int i = 0; i < size; i++) values.add(buf.readString(8_192));
            return new ConfigEntry(path, type, List.copyOf(values));
        }

        void write(RegistryByteBuf buf) {
            buf.writeString(path, 512);
            buf.writeEnumConstant(type);
            buf.writeVarInt(values.size());
            for (String value : values) buf.writeString(value, 8_192);
        }
    }

    /** Conservative server validation envelope; never used to draw an ability. */
    public record AbilityProfile(String name, String element, VisualKind visualKind, double speed,
                                 double range, double radius, long chargeMillis, long cooldownMillis,
                                 String tempMaterial, boolean harmless, boolean sneakAbility) {
        static AbilityProfile read(RegistryByteBuf buf) {
            return new AbilityProfile(buf.readString(128), buf.readString(64), buf.readEnumConstant(VisualKind.class),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readLong(), buf.readLong(),
                    buf.readString(128), buf.readBoolean(), buf.readBoolean());
        }

        void write(RegistryByteBuf buf) {
            buf.writeString(name, 128);
            buf.writeString(element, 64);
            buf.writeEnumConstant(visualKind);
            buf.writeDouble(speed);
            buf.writeDouble(range);
            buf.writeDouble(radius);
            buf.writeLong(chargeMillis);
            buf.writeLong(cooldownMillis);
            buf.writeString(tempMaterial, 128);
            buf.writeBoolean(harmless);
            buf.writeBoolean(sneakAbility);
        }
    }

    public record ClientHello(int protocolVersion, long clientTick, int capabilities) implements CustomPayload {
        public static final Id<ClientHello> ID = id("client_hello");
        public static final PacketCodec<RegistryByteBuf, ClientHello> CODEC = PacketCodec.of(ClientHello::write, ClientHello::new);
        private ClientHello(RegistryByteBuf buf) { this(buf.readVarInt(), buf.readLong(), buf.readVarInt()); }
        private void write(RegistryByteBuf buf) { buf.writeVarInt(protocolVersion); buf.writeLong(clientTick); buf.writeVarInt(capabilities); }
        @Override public Id<ClientHello> getId() { return ID; }
    }

    public record ServerSnapshot(int protocolVersion, UUID sessionId, long serverTick, long serverNowMillis, long configEpoch,
                                 int maxRewindTicks, List<ConfigEntry> config, List<AbilityProfile> profiles,
                                 Map<Integer, String> binds, Map<String, Long> cooldowns,
                                 List<String> elements, List<String> subElements, double airBlastDecay) implements CustomPayload {
        public static final Id<ServerSnapshot> ID = id("server_snapshot");
        public static final PacketCodec<RegistryByteBuf, ServerSnapshot> CODEC = PacketCodec.of(ServerSnapshot::write, ServerSnapshot::new);

        private ServerSnapshot(RegistryByteBuf buf) {
            this(buf.readVarInt(), buf.readUuid(), buf.readLong(), buf.readLong(), buf.readLong(), buf.readVarInt(),
                    readConfig(buf), readProfiles(buf), readBinds(buf), readCooldowns(buf), readStrings(buf, 64), readStrings(buf, 128),
                    buf.readDouble());
        }

        private void write(RegistryByteBuf buf) {
            buf.writeVarInt(protocolVersion);
            buf.writeUuid(sessionId);
            buf.writeLong(serverTick);
            buf.writeLong(serverNowMillis);
            buf.writeLong(configEpoch);
            buf.writeVarInt(maxRewindTicks);
            buf.writeVarInt(config.size());
            config.forEach(entry -> entry.write(buf));
            buf.writeVarInt(profiles.size());
            profiles.forEach(profile -> profile.write(buf));
            buf.writeVarInt(binds.size());
            binds.forEach((slot, ability) -> { buf.writeVarInt(slot); buf.writeString(ability, 128); });
            buf.writeVarInt(cooldowns.size());
            cooldowns.forEach((ability, until) -> { buf.writeString(ability, 128); buf.writeLong(until); });
            writeStrings(buf, elements);
            writeStrings(buf, subElements);
            buf.writeDouble(airBlastDecay);
        }

        @Override public Id<ServerSnapshot> getId() { return ID; }
    }

    public record PlayerState(UUID sessionId, long serverTick, long serverNowMillis, long acknowledgedSequence, Map<Integer, String> binds,
                              Map<String, Long> cooldowns, List<String> elements,
                              List<String> subElements, double airBlastDecay,
                              List<String> activeFlightAbilities) implements CustomPayload {
        public static final Id<PlayerState> ID = id("player_state");
        public static final PacketCodec<RegistryByteBuf, PlayerState> CODEC = PacketCodec.of(PlayerState::write, PlayerState::new);
        private PlayerState(RegistryByteBuf buf) { this(buf.readUuid(), buf.readLong(), buf.readLong(), buf.readVarLong(), readBinds(buf), readCooldowns(buf), readStrings(buf, 64), readStrings(buf, 128), buf.readDouble(), readStrings(buf, 32)); }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeLong(serverTick); buf.writeLong(serverNowMillis); buf.writeVarLong(acknowledgedSequence);
            buf.writeVarInt(binds.size());
            binds.forEach((slot, ability) -> { buf.writeVarInt(slot); buf.writeString(ability, 128); });
            buf.writeVarInt(cooldowns.size());
            cooldowns.forEach((ability, until) -> { buf.writeString(ability, 128); buf.writeLong(until); });
            writeStrings(buf, elements);
            writeStrings(buf, subElements);
            buf.writeDouble(airBlastDecay);
            writeStrings(buf, activeFlightAbilities);
        }
        @Override public Id<PlayerState> getId() { return ID; }
    }

    /** Explicit server-side state mutations, never periodic cooldown timing. */
    public record StateDirective(UUID sessionId, String removedCooldown, String addedCooldown,
                                 long cooldownUntil, long serverNowMillis, boolean resetAirBlast,
                                 double airBlastDecay) implements CustomPayload {
        public static final Id<StateDirective> ID = id("state_directive");
        public static final PacketCodec<RegistryByteBuf, StateDirective> CODEC = PacketCodec.of(StateDirective::write, StateDirective::new);
        private StateDirective(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readString(128), buf.readString(128), buf.readLong(), buf.readLong(),
                    buf.readBoolean(), buf.readDouble());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeString(removedCooldown, 128); buf.writeString(addedCooldown, 128);
            buf.writeLong(cooldownUntil); buf.writeLong(serverNowMillis); buf.writeBoolean(resetAirBlast); buf.writeDouble(airBlastDecay);
        }
        @Override public Id<StateDirective> getId() { return ID; }
    }

    /** Ordered overflow chunks sent by Paper before the matching snapshot. */
    public record ConfigChunk(UUID sessionId, long configEpoch, int chunkIndex, int chunkCount,
                              List<ConfigEntry> config) implements CustomPayload {
        public static final Id<ConfigChunk> ID = id("config_chunk");
        public static final PacketCodec<RegistryByteBuf, ConfigChunk> CODEC = PacketCodec.of(ConfigChunk::write, ConfigChunk::new);
        private ConfigChunk(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readLong(), buf.readVarInt(), buf.readVarInt(), readConfig(buf));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeLong(configEpoch); buf.writeVarInt(chunkIndex); buf.writeVarInt(chunkCount);
            buf.writeVarInt(config.size()); config.forEach(entry -> entry.write(buf));
        }
        @Override public Id<ConfigChunk> getId() { return ID; }
    }

    public record InputFrame(UUID sessionId, long sequence, long clientTick, InputKind kind, int selectedSlot,
                             float yaw, float pitch, double claimedOriginX, double claimedOriginY,
                             double claimedOriginZ, boolean locallyPredicted,
                             boolean locallyBlockedByCooldown) implements CustomPayload {
        public static final Id<InputFrame> ID = id("input_frame");
        public static final PacketCodec<RegistryByteBuf, InputFrame> CODEC = PacketCodec.of(InputFrame::write, InputFrame::new);
        private InputFrame(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readLong(), buf.readEnumConstant(InputKind.class), buf.readVarInt(),
                    buf.readFloat(), buf.readFloat(), buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readBoolean(), buf.readBoolean());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(sequence); buf.writeLong(clientTick); buf.writeEnumConstant(kind);
            buf.writeVarInt(selectedSlot); buf.writeFloat(yaw); buf.writeFloat(pitch);
            buf.writeDouble(claimedOriginX); buf.writeDouble(claimedOriginY); buf.writeDouble(claimedOriginZ);
            buf.writeBoolean(locallyPredicted);
            buf.writeBoolean(locallyBlockedByCooldown);
        }
        @Override public Id<InputFrame> getId() { return ID; }
    }

    public record ActionPrepare(UUID sessionId, long sequence, long clientTick, InputKind kind, int selectedSlot,
                                float yaw, float pitch, double claimedOriginX, double claimedOriginY,
                                double claimedOriginZ) implements CustomPayload {
        public static final Id<ActionPrepare> ID = id("action_prepare");
        public static final PacketCodec<RegistryByteBuf, ActionPrepare> CODEC = PacketCodec.of(ActionPrepare::write, ActionPrepare::new);
        private ActionPrepare(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readLong(), buf.readEnumConstant(InputKind.class), buf.readVarInt(),
                    buf.readFloat(), buf.readFloat(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(sequence); buf.writeLong(clientTick); buf.writeEnumConstant(kind);
            buf.writeVarInt(selectedSlot); buf.writeFloat(yaw); buf.writeFloat(pitch);
            buf.writeDouble(claimedOriginX); buf.writeDouble(claimedOriginY); buf.writeDouble(claimedOriginZ);
        }
        @Override public Id<ActionPrepare> getId() { return ID; }
    }

    public record Reconcile(UUID sessionId, long sequence, boolean accepted, String reason, long serverTick, long serverNowMillis,
                            String ability, double originX, double originY, double originZ,
                            long cooldownUntil) implements CustomPayload {
        public static final Id<Reconcile> ID = id("reconcile");
        public static final PacketCodec<RegistryByteBuf, Reconcile> CODEC = PacketCodec.of(Reconcile::write, Reconcile::new);
        private Reconcile(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readBoolean(), buf.readString(128), buf.readLong(), buf.readLong(),
                    buf.readString(128), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readLong());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(sequence); buf.writeBoolean(accepted); buf.writeString(reason, 128);
            buf.writeLong(serverTick); buf.writeLong(serverNowMillis); buf.writeString(ability, 128); buf.writeDouble(originX); buf.writeDouble(originY);
            buf.writeDouble(originZ); buf.writeLong(cooldownUntil);
        }
        @Override public Id<Reconcile> getId() { return ID; }
    }

    public record HitClaim(UUID sessionId, long actionSequence, long clientTick, int targetEntityId,
                           double contactX, double contactY, double contactZ) implements CustomPayload {
        public static final Id<HitClaim> ID = id("hit_claim");
        public static final PacketCodec<RegistryByteBuf, HitClaim> CODEC = PacketCodec.of(HitClaim::write, HitClaim::new);
        private HitClaim(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readLong(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(actionSequence); buf.writeLong(clientTick); buf.writeVarInt(targetEntityId);
            buf.writeDouble(contactX); buf.writeDouble(contactY); buf.writeDouble(contactZ);
        }
        @Override public Id<HitClaim> getId() { return ID; }
    }

    public record AuthorityHandoff(UUID sessionId, long actionSequence) implements CustomPayload {
        public static final Id<AuthorityHandoff> ID = id("authority_handoff");
        public static final PacketCodec<RegistryByteBuf, AuthorityHandoff> CODEC = PacketCodec.of(AuthorityHandoff::write, AuthorityHandoff::new);
        private AuthorityHandoff(RegistryByteBuf buf) { this(buf.readUuid(), buf.readVarLong()); }
        private void write(RegistryByteBuf buf) { buf.writeUuid(sessionId); buf.writeVarLong(actionSequence); }
        @Override public Id<AuthorityHandoff> getId() { return ID; }
    }

    public record TempBlockOp(TempOperation operation, String world, int x, int y, int z,
                              String material, long revertAtMillis, long actionSequence,
                              long layerId, long revision, UUID ownerId, String viewerMaterial) {
        static TempBlockOp read(RegistryByteBuf buf) {
            return new TempBlockOp(buf.readEnumConstant(TempOperation.class), buf.readString(256), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readString(128), buf.readLong(), buf.readVarLong(), buf.readVarLong(),
                    buf.readVarLong(), buf.readBoolean() ? buf.readUuid() : null, buf.readString(128));
        }
        void write(RegistryByteBuf buf) {
            buf.writeEnumConstant(operation); buf.writeString(world, 256); buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
            buf.writeString(material, 128); buf.writeLong(revertAtMillis); buf.writeVarLong(actionSequence);
            buf.writeVarLong(layerId); buf.writeVarLong(revision); buf.writeBoolean(ownerId != null);
            if (ownerId != null) buf.writeUuid(ownerId);
            buf.writeString(viewerMaterial, 128);
        }
    }

    public record TempBlockBatch(long serverTick, long serverNowMillis, List<TempBlockOp> operations) implements CustomPayload {
        public static final Id<TempBlockBatch> ID = id("temp_blocks");
        public static final PacketCodec<RegistryByteBuf, TempBlockBatch> CODEC = PacketCodec.of(TempBlockBatch::write, TempBlockBatch::new);
        private TempBlockBatch(RegistryByteBuf buf) {
            this(buf.readLong(), buf.readLong(), readTempOps(buf));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeLong(serverTick); buf.writeLong(serverNowMillis); buf.writeVarInt(operations.size()); operations.forEach(operation -> operation.write(buf));
        }
        @Override public Id<TempBlockBatch> getId() { return ID; }
    }

    /** Ownership receipt sent immediately before the matching vanilla velocity update. */
    public record VelocityOwner(long serverTick, long actionSequence, int impulseOrdinal,
                                UUID abilityOwner, UUID target, String ability) implements CustomPayload {
        public static final Id<VelocityOwner> ID = id("velocity_owner");
        public static final PacketCodec<RegistryByteBuf, VelocityOwner> CODEC = PacketCodec.of(VelocityOwner::write, VelocityOwner::new);
        private VelocityOwner(RegistryByteBuf buf) {
            this(buf.readLong(), buf.readVarLong(), buf.readVarInt(), buf.readUuid(), buf.readUuid(),
                    buf.readString(128));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeLong(serverTick); buf.writeVarLong(actionSequence); buf.writeVarInt(impulseOrdinal);
            buf.writeUuid(abilityOwner); buf.writeUuid(target); buf.writeString(ability, 128);
        }
        @Override public Id<VelocityOwner> getId() { return ID; }
    }

    public record VelocityOwnerV2(long serverTick, long actionSequence, int impulseOrdinal,
                                  UUID abilityOwner, UUID target, int targetEntityId, String ability,
                                  double velocityX, double velocityY, double velocityZ) implements CustomPayload {
        public static final Id<VelocityOwnerV2> ID = id("velocity_owner_v2");
        public static final PacketCodec<RegistryByteBuf, VelocityOwnerV2> CODEC = PacketCodec.of(VelocityOwnerV2::write, VelocityOwnerV2::new);
        private VelocityOwnerV2(RegistryByteBuf buf) {
            this(buf.readLong(), buf.readVarLong(), buf.readVarInt(), buf.readUuid(), buf.readUuid(), buf.readVarInt(),
                    buf.readString(128), buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeLong(serverTick); buf.writeVarLong(actionSequence); buf.writeVarInt(impulseOrdinal);
            buf.writeUuid(abilityOwner); buf.writeUuid(target); buf.writeVarInt(targetEntityId); buf.writeString(ability, 128);
            buf.writeDouble(velocityX); buf.writeDouble(velocityY); buf.writeDouble(velocityZ);
        }
        @Override public Id<VelocityOwnerV2> getId() { return ID; }
    }

    public record AbilityRemoved(UUID player, String ability, long actionSequence) implements CustomPayload {
        public static final Id<AbilityRemoved> ID = id("ability_removed");
        public static final PacketCodec<RegistryByteBuf, AbilityRemoved> CODEC = PacketCodec.of(AbilityRemoved::write, AbilityRemoved::new);
        private AbilityRemoved(RegistryByteBuf buf) { this(buf.readUuid(), buf.readString(128), buf.readVarLong()); }
        private void write(RegistryByteBuf buf) { buf.writeUuid(player); buf.writeString(ability, 128); buf.writeVarLong(actionSequence); }
        @Override public Id<AbilityRemoved> getId() { return ID; }
    }

    public static synchronized void registerTypes() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.playC2S().register(ClientHello.ID, ClientHello.CODEC);
        PayloadTypeRegistry.playC2S().register(InputFrame.ID, InputFrame.CODEC);
        PayloadTypeRegistry.playC2S().register(ActionPrepare.ID, ActionPrepare.CODEC);
        PayloadTypeRegistry.playC2S().register(HitClaim.ID, HitClaim.CODEC);
        PayloadTypeRegistry.playC2S().register(AuthorityHandoff.ID, AuthorityHandoff.CODEC);
        PayloadTypeRegistry.playS2C().registerLarge(ServerSnapshot.ID, ServerSnapshot.CODEC, 2 * 1024 * 1024);
        PayloadTypeRegistry.playS2C().register(PlayerState.ID, PlayerState.CODEC);
        PayloadTypeRegistry.playS2C().register(StateDirective.ID, StateDirective.CODEC);
        PayloadTypeRegistry.playS2C().registerLarge(ConfigChunk.ID, ConfigChunk.CODEC, 2 * 1024 * 1024);
        PayloadTypeRegistry.playS2C().register(Reconcile.ID, Reconcile.CODEC);
        PayloadTypeRegistry.playS2C().registerLarge(TempBlockBatch.ID, TempBlockBatch.CODEC, 512 * 1024);
        PayloadTypeRegistry.playS2C().register(VelocityOwner.ID, VelocityOwner.CODEC);
        PayloadTypeRegistry.playS2C().register(VelocityOwnerV2.ID, VelocityOwnerV2.CODEC);
        PayloadTypeRegistry.playS2C().register(AbilityRemoved.ID, AbilityRemoved.CODEC);
    }

    private static List<ConfigEntry> readConfig(RegistryByteBuf buf) {
        int size = bounded(buf.readVarInt(), MAX_CONFIG_ENTRIES, "config entries");
        List<ConfigEntry> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(ConfigEntry.read(buf));
        return List.copyOf(result);
    }

    private static List<AbilityProfile> readProfiles(RegistryByteBuf buf) {
        int size = bounded(buf.readVarInt(), MAX_PROFILES, "ability profiles");
        List<AbilityProfile> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(AbilityProfile.read(buf));
        return List.copyOf(result);
    }

    private static Map<Integer, String> readBinds(RegistryByteBuf buf) {
        int size = bounded(buf.readVarInt(), 9, "binds");
        Map<Integer, String> result = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) result.put(buf.readVarInt(), buf.readString(128));
        return Map.copyOf(result);
    }

    private static Map<String, Long> readCooldowns(RegistryByteBuf buf) {
        int size = bounded(buf.readVarInt(), MAX_PROFILES, "cooldowns");
        Map<String, Long> result = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) result.put(buf.readString(128), buf.readLong());
        return Map.copyOf(result);
    }

    private static List<TempBlockOp> readTempOps(RegistryByteBuf buf) {
        int size = bounded(buf.readVarInt(), MAX_TEMP_OPS, "temporary block operations");
        List<TempBlockOp> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(TempBlockOp.read(buf));
        return List.copyOf(result);
    }

    private static List<String> readStrings(RegistryByteBuf buf, int maximum) {
        int size = bounded(buf.readVarInt(), maximum, "string list");
        List<String> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(buf.readString(128));
        return List.copyOf(result);
    }

    private static void writeStrings(RegistryByteBuf buf, List<String> values) {
        buf.writeVarInt(values.size());
        for (String value : values) buf.writeString(value, 128);
    }

    private static int bounded(int value, int maximum, String label) {
        if (value < 0 || value > maximum) throw new IllegalArgumentException("Invalid " + label + ": " + value);
        return value;
    }

    private static <T extends CustomPayload> CustomPayload.Id<T> id(String path) {
        return new CustomPayload.Id<>(Identifier.of("projectkorra", path));
    }
}

package com.projectkorra.projectkorra.fabric.prediction.protocol;

import com.projectkorra.projectkorra.prediction.authority.RegionProtectionAuthority;
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
    public static final int PROTOCOL_VERSION = 47;
    public static final int MAX_BLOCK_STATE_CHARACTERS = 512;
    public static final int MAX_CONFIG_ENTRIES = 16_384;
    public static final int MAX_PROFILES = 2_048;
    public static final int MAX_TEMP_OPS = 4_096;
    private static boolean registered;

    private PredictionPayloads() { }

    public enum InputKind { LEFT_CLICK, RIGHT_CLICK, RIGHT_CLICK_BLOCK, RIGHT_CLICK_ENTITY, SNEAK_START, SNEAK_STOP, SWAP_HANDS }
    public enum VisualKind { CAST, PROJECTILE, AREA, TEMP_BLOCK, SELF }
    public enum ValueType { STRING, BOOLEAN, INTEGER, DECIMAL, STRING_LIST }
    public enum TempOperation { CREATE, UPDATE_EXPIRY, REVERT, DISCARD }

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

    /** One-time session barrier. Gameplay input continues to use vanilla packets only. */
    public record ClientReady(UUID sessionId, List<String> supportedAbilities) implements CustomPayload {
        public static final Id<ClientReady> ID = id("client_ready");
        public static final PacketCodec<RegistryByteBuf, ClientReady> CODEC = PacketCodec.of(ClientReady::write, ClientReady::new);
        private ClientReady(RegistryByteBuf buf) {
            this(buf.readUuid(), readStrings(buf, MAX_PROFILES));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId);
            writeStrings(buf, supportedAbilities);
        }
        @Override public Id<ClientReady> getId() { return ID; }
    }

    /**
     * Opaque authoritative world identity. Paper uses the Bukkit world UUID,
     * because multiple worlds can share one vanilla dimension key and one
     * ClientWorld instance during a server-side transfer.
     */
    public record ServerWorldState(UUID sessionId, long worldGeneration,
                                   String worldIdentity) implements CustomPayload {
        public static final Id<ServerWorldState> ID = id("world_state");
        public static final PacketCodec<RegistryByteBuf, ServerWorldState> CODEC =
                PacketCodec.of(ServerWorldState::write, ServerWorldState::new);
        private ServerWorldState(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readString(128));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(worldGeneration); buf.writeString(worldIdentity, 128);
        }
        @Override public Id<ServerWorldState> getId() { return ID; }
    }

    /**
     * Negative-only metadata for a native input which the local common runtime
     * rejected while its cooldown was still active. The following vanilla
     * packet remains the sole input/scheduling authority; this message can
     * only prevent ProjectKorra from replaying an already-rejected cast after
     * network latency, never make the server accept one.
     */
    public record InputVeto(UUID sessionId, long actionSequence, InputKind kind,
                            String ability) implements CustomPayload {
        public static final Id<InputVeto> ID = id("input_veto");
        public static final PacketCodec<RegistryByteBuf, InputVeto> CODEC =
                PacketCodec.of(InputVeto::write, InputVeto::new);
        private InputVeto(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readEnumConstant(InputKind.class),
                    buf.readString(128));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(actionSequence);
            buf.writeEnumConstant(kind); buf.writeString(ability, 128);
        }
        @Override public Id<InputVeto> getId() { return ID; }
    }

    /**
     * Correlates the immediately following accepted vanilla input with the
     * local prediction id.
     * This metadata cannot schedule or authorize an ability.
     */
    public record ActionTag(UUID sessionId, long clientActionSequence, InputKind kind,
                            int selectedSlot, String ability) implements CustomPayload {
        public static final Id<ActionTag> ID = id("action_tag");
        public static final PacketCodec<RegistryByteBuf, ActionTag> CODEC =
                PacketCodec.of(ActionTag::write, ActionTag::new);
        private ActionTag(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readEnumConstant(InputKind.class),
                    buf.readVarInt(), buf.readString(128));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(clientActionSequence);
            buf.writeEnumConstant(kind); buf.writeVarInt(selectedSlot); buf.writeString(ability, 128);
        }
        @Override public Id<ActionTag> getId() { return ID; }
    }

    /** Client-observed contact evidence; the server still owns hit resolution. */
    public record HitClaim(UUID sessionId, long clientActionSequence, long serverActionSequence,
                           long clientTick, UUID targetUuid, int targetEntityId, String ability,
                           double contactX, double contactY, double contactZ) implements CustomPayload {
        public static final Id<HitClaim> ID = id("hit_claim");
        public static final PacketCodec<RegistryByteBuf, HitClaim> CODEC =
                PacketCodec.of(HitClaim::write, HitClaim::new);
        private HitClaim(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readVarLong(), buf.readLong(),
                    buf.readUuid(), buf.readVarInt(), buf.readString(128),
                    buf.readDouble(), buf.readDouble(), buf.readDouble());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(clientActionSequence);
            buf.writeVarLong(serverActionSequence); buf.writeLong(clientTick);
            buf.writeUuid(targetUuid); buf.writeVarInt(targetEntityId); buf.writeString(ability, 128);
            buf.writeDouble(contactX); buf.writeDouble(contactY); buf.writeDouble(contactZ);
        }
        @Override public Id<HitClaim> getId() { return ID; }
    }

    public record ServerSnapshot(int protocolVersion, UUID sessionId, long serverTick, long serverNowMillis, long configEpoch,
                                 int maxRewindTicks, List<ConfigEntry> config, List<AbilityProfile> profiles,
                                 Map<Integer, String> binds, Map<String, Long> cooldowns,
                                 List<String> elements, List<String> subElements,
                                 List<String> permissions, double airBlastDecay, boolean chiBlocked,
                                 RegionProtectionAuthority.Snapshot regionProtection) implements CustomPayload {
        public static final Id<ServerSnapshot> ID = id("server_snapshot");
        public static final PacketCodec<RegistryByteBuf, ServerSnapshot> CODEC = PacketCodec.of(ServerSnapshot::write, ServerSnapshot::new);

        private ServerSnapshot(RegistryByteBuf buf) {
            this(buf.readVarInt(), buf.readUuid(), buf.readLong(), buf.readLong(), buf.readLong(), buf.readVarInt(),
                    readConfig(buf), readProfiles(buf), readBinds(buf), readCooldowns(buf), readStrings(buf, 64), readStrings(buf, 128),
                    readStrings(buf, 2_048), buf.readDouble(), buf.readBoolean(), readRegionProtection(buf));
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
            writeStrings(buf, permissions);
            buf.writeDouble(airBlastDecay); buf.writeBoolean(chiBlocked);
            writeRegionProtection(buf, regionProtection);
        }

        @Override public Id<ServerSnapshot> getId() { return ID; }
    }

    public record PlayerState(UUID sessionId, long serverTick, long serverNowMillis, long acknowledgedSequence, Map<Integer, String> binds,
                              Map<String, Long> cooldowns, List<String> elements,
                              List<String> subElements, List<String> permissions, double airBlastDecay,
                              boolean chiBlocked, RegionProtectionAuthority.Snapshot regionProtection,
                              List<String> activeFlightAbilities) implements CustomPayload {
        public static final Id<PlayerState> ID = id("player_state");
        public static final PacketCodec<RegistryByteBuf, PlayerState> CODEC = PacketCodec.of(PlayerState::write, PlayerState::new);
        private PlayerState(RegistryByteBuf buf) { this(buf.readUuid(), buf.readLong(), buf.readLong(), buf.readVarLong(), readBinds(buf), readCooldowns(buf), readStrings(buf, 64), readStrings(buf, 128), readStrings(buf, 2_048), buf.readDouble(), buf.readBoolean(), readRegionProtection(buf), readStrings(buf, 32)); }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeLong(serverTick); buf.writeLong(serverNowMillis); buf.writeVarLong(acknowledgedSequence);
            buf.writeVarInt(binds.size());
            binds.forEach((slot, ability) -> { buf.writeVarInt(slot); buf.writeString(ability, 128); });
            buf.writeVarInt(cooldowns.size());
            cooldowns.forEach((ability, until) -> { buf.writeString(ability, 128); buf.writeLong(until); });
            writeStrings(buf, elements);
            writeStrings(buf, subElements);
            writeStrings(buf, permissions);
            buf.writeDouble(airBlastDecay); buf.writeBoolean(chiBlocked);
            writeRegionProtection(buf, regionProtection);
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

    /** Causal id assigned by the server immediately before the native input callback runs. */
    public record NativeAction(UUID sessionId, long actionSequence, long clientActionSequence,
                               long serverTick, InputKind kind,
                               int selectedSlot, String ability, double originX, double originY,
                               double originZ, float yaw, float pitch,
                               boolean predictable) implements CustomPayload {
        public static final Id<NativeAction> ID = id("native_action");
        public static final PacketCodec<RegistryByteBuf, NativeAction> CODEC = PacketCodec.of(NativeAction::write, NativeAction::new);
        private NativeAction(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readVarLong(), buf.readLong(),
                    buf.readEnumConstant(InputKind.class),
                    buf.readVarInt(), buf.readString(128), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readFloat(), buf.readFloat(), buf.readBoolean());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(actionSequence);
            buf.writeVarLong(clientActionSequence); buf.writeLong(serverTick);
            buf.writeEnumConstant(kind); buf.writeVarInt(selectedSlot); buf.writeString(ability, 128);
            buf.writeDouble(originX); buf.writeDouble(originY); buf.writeDouble(originZ);
            buf.writeFloat(yaw); buf.writeFloat(pitch); buf.writeBoolean(predictable);
        }
        @Override public Id<NativeAction> getId() { return ID; }
    }

    /** Read-only evidence comparing the AirBlast state actually used by both runtimes. */
    public record Reconcile(UUID sessionId, long sequence, boolean accepted, String reason, long serverTick, long serverNowMillis,
                            String ability, double originX, double originY, double originZ,
                            long cooldownUntil, boolean inputHandled, boolean comboRecorded,
                            List<String> createdAbilities) implements CustomPayload {
        public static final Id<Reconcile> ID = id("reconcile");
        public static final PacketCodec<RegistryByteBuf, Reconcile> CODEC = PacketCodec.of(Reconcile::write, Reconcile::new);
        private Reconcile(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readBoolean(), buf.readString(128), buf.readLong(), buf.readLong(),
                    buf.readString(128), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readLong(),
                    buf.readBoolean(), buf.readBoolean(), readStrings(buf, 64));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(sequence); buf.writeBoolean(accepted); buf.writeString(reason, 128);
            buf.writeLong(serverTick); buf.writeLong(serverNowMillis); buf.writeString(ability, 128); buf.writeDouble(originX); buf.writeDouble(originY);
            buf.writeDouble(originZ); buf.writeLong(cooldownUntil); buf.writeBoolean(inputHandled);
            buf.writeBoolean(comboRecorded);
            writeStrings(buf, createdAbilities);
        }
        @Override public Id<Reconcile> getId() { return ID; }
    }

    public record TempBlockOp(TempOperation operation, String world, int x, int y, int z,
                              String material, long revertAtMillis, long actionSequence,
                              String effectAbility, String effectState, long effectStep, int effectOrdinal,
                              long layerId, long revision, UUID ownerId, String viewerMaterial,
                              boolean packetExpected) {
        static TempBlockOp read(RegistryByteBuf buf) {
            return new TempBlockOp(buf.readEnumConstant(TempOperation.class), buf.readString(256), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readString(MAX_BLOCK_STATE_CHARACTERS), buf.readLong(), buf.readVarLong(),
                    buf.readString(128), buf.readString(64), buf.readLong(), buf.readVarInt(), buf.readVarLong(), buf.readVarLong(),
                    buf.readBoolean() ? buf.readUuid() : null,
                    buf.readString(MAX_BLOCK_STATE_CHARACTERS), buf.readBoolean());
        }
        void write(RegistryByteBuf buf) {
            buf.writeEnumConstant(operation); buf.writeString(world, 256); buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
            buf.writeString(material, MAX_BLOCK_STATE_CHARACTERS); buf.writeLong(revertAtMillis); buf.writeVarLong(actionSequence);
            buf.writeString(effectAbility, 128); buf.writeString(effectState, 64);
            buf.writeLong(effectStep); buf.writeVarInt(effectOrdinal);
            buf.writeVarLong(layerId); buf.writeVarLong(revision); buf.writeBoolean(ownerId != null);
            if (ownerId != null) buf.writeUuid(ownerId);
            buf.writeString(viewerMaterial, MAX_BLOCK_STATE_CHARACTERS); buf.writeBoolean(packetExpected);
        }
    }

    public record TempBlockBatch(UUID sessionId, long worldGeneration, String worldIdentity,
                                 boolean snapshot,
                                 long serverTick, long serverNowMillis,
                                 List<TempBlockOp> operations) implements CustomPayload {
        public static final Id<TempBlockBatch> ID = id("temp_blocks");
        public static final PacketCodec<RegistryByteBuf, TempBlockBatch> CODEC = PacketCodec.of(TempBlockBatch::write, TempBlockBatch::new);
        private TempBlockBatch(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readString(128), buf.readBoolean(),
                    buf.readLong(), buf.readLong(), readTempOps(buf));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(sessionId); buf.writeVarLong(worldGeneration); buf.writeString(worldIdentity, 128);
            buf.writeBoolean(snapshot);
            buf.writeLong(serverTick); buf.writeLong(serverNowMillis); buf.writeVarInt(operations.size());
            operations.forEach(operation -> operation.write(buf));
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

    /** Ownership fence sent immediately before one vanilla abilities packet. */
    public record AbilityStateOwner(long serverTick, long actionSequence, int mutationOrdinal,
                                    UUID abilityOwner, UUID target, String ability,
                                    boolean flying, boolean allowFlight,
                                    float flySpeed) implements CustomPayload {
        public static final Id<AbilityStateOwner> ID = id("ability_state_owner");
        public static final PacketCodec<RegistryByteBuf, AbilityStateOwner> CODEC =
                PacketCodec.of(AbilityStateOwner::write, AbilityStateOwner::new);
        private AbilityStateOwner(RegistryByteBuf buf) {
            this(buf.readLong(), buf.readVarLong(), buf.readVarInt(), buf.readUuid(),
                    buf.readUuid(), buf.readString(128), buf.readBoolean(),
                    buf.readBoolean(), buf.readFloat());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeLong(serverTick); buf.writeVarLong(actionSequence); buf.writeVarInt(mutationOrdinal);
            buf.writeUuid(abilityOwner); buf.writeUuid(target); buf.writeString(ability, 128);
            buf.writeBoolean(flying); buf.writeBoolean(allowFlight); buf.writeFloat(flySpeed);
        }
        @Override public Id<AbilityStateOwner> getId() { return ID; }
    }

    /** Exact ownership of one server TempFallingBlock, sent only to its caster. */
    public record TempFallingBlockPrepare(long serverTick, long actionSequence, int spawnOrdinal,
                                          UUID abilityOwner, String ability, String world,
                                          double x, double y, double z,
                                          String material) implements CustomPayload {
        public static final Id<TempFallingBlockPrepare> ID = id("temp_falling_prepare");
        public static final PacketCodec<RegistryByteBuf, TempFallingBlockPrepare> CODEC =
                PacketCodec.of(TempFallingBlockPrepare::write, TempFallingBlockPrepare::new);
        private TempFallingBlockPrepare(RegistryByteBuf buf) {
            this(buf.readLong(), buf.readVarLong(), buf.readVarInt(), buf.readUuid(),
                    buf.readString(128), buf.readString(256), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readString(MAX_BLOCK_STATE_CHARACTERS));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeLong(serverTick); buf.writeVarLong(actionSequence); buf.writeVarInt(spawnOrdinal);
            buf.writeUuid(abilityOwner); buf.writeString(ability, 128); buf.writeString(world, 256);
            buf.writeDouble(x); buf.writeDouble(y); buf.writeDouble(z);
            buf.writeString(material, MAX_BLOCK_STATE_CHARACTERS);
        }
        @Override public Id<TempFallingBlockPrepare> getId() { return ID; }
    }

    public record TempFallingBlockReceipt(long serverTick, long actionSequence, int spawnOrdinal,
                                          UUID abilityOwner, int serverEntityId,
                                          String ability) implements CustomPayload {
        public static final Id<TempFallingBlockReceipt> ID = id("temp_falling_block");
        public static final PacketCodec<RegistryByteBuf, TempFallingBlockReceipt> CODEC =
                PacketCodec.of(TempFallingBlockReceipt::write, TempFallingBlockReceipt::new);
        private TempFallingBlockReceipt(RegistryByteBuf buf) {
            this(buf.readLong(), buf.readVarLong(), buf.readVarInt(), buf.readUuid(),
                    buf.readVarInt(), buf.readString(128));
        }
        private void write(RegistryByteBuf buf) {
            buf.writeLong(serverTick); buf.writeVarLong(actionSequence); buf.writeVarInt(spawnOrdinal);
            buf.writeUuid(abilityOwner); buf.writeVarInt(serverEntityId); buf.writeString(ability, 128);
        }
        @Override public Id<TempFallingBlockReceipt> getId() { return ID; }
    }

    /** Exact causal identity of one client-predictable ordinary earth write. */
    public record DirectBlockReceipt(long serverTick, long actionSequence, int mutationOrdinal,
                                     UUID abilityOwner, String ability, String world,
                                     int x, int y, int z, String material,
                                     boolean movedEarthLifecycle) implements CustomPayload {
        public static final Id<DirectBlockReceipt> ID = id("direct_block");
        public static final PacketCodec<RegistryByteBuf, DirectBlockReceipt> CODEC =
                PacketCodec.of(DirectBlockReceipt::write, DirectBlockReceipt::new);
        private DirectBlockReceipt(RegistryByteBuf buf) {
            this(buf.readLong(), buf.readVarLong(), buf.readVarInt(), buf.readUuid(),
                    buf.readString(128), buf.readString(256), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readString(MAX_BLOCK_STATE_CHARACTERS), buf.readBoolean());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeLong(serverTick); buf.writeVarLong(actionSequence); buf.writeVarInt(mutationOrdinal);
            buf.writeUuid(abilityOwner); buf.writeString(ability, 128); buf.writeString(world, 256);
            buf.writeInt(x); buf.writeInt(y); buf.writeInt(z); buf.writeString(material, MAX_BLOCK_STATE_CHARACTERS);
            buf.writeBoolean(movedEarthLifecycle);
        }
        @Override public Id<DirectBlockReceipt> getId() { return ID; }
    }

    public record AbilityRemoved(UUID player, String ability, String abilityType, long actionSequence,
                                 boolean externallyCaused, boolean predictionRejected,
                                 long acknowledgedSequence,
                                 int remainingTypeInstances) implements CustomPayload {
        public static final Id<AbilityRemoved> ID = id("ability_removed");
        public static final PacketCodec<RegistryByteBuf, AbilityRemoved> CODEC = PacketCodec.of(AbilityRemoved::write, AbilityRemoved::new);
        private AbilityRemoved(RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readString(128), buf.readString(256), buf.readVarLong(),
                    buf.readBoolean(), buf.readBoolean(), buf.readVarLong(), buf.readVarInt());
        }
        private void write(RegistryByteBuf buf) {
            buf.writeUuid(player); buf.writeString(ability, 128); buf.writeString(abilityType, 256);
            buf.writeVarLong(actionSequence);
            buf.writeBoolean(externallyCaused);
            buf.writeBoolean(predictionRejected);
            buf.writeVarLong(acknowledgedSequence);
            buf.writeVarInt(remainingTypeInstances);
        }
        @Override public Id<AbilityRemoved> getId() { return ID; }
    }

    public record AbilityTransferBlock(int x, int y, int z, String material) {
        static AbilityTransferBlock read(final RegistryByteBuf buf) {
            return new AbilityTransferBlock(buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readString(MAX_BLOCK_STATE_CHARACTERS));
        }

        void write(final RegistryByteBuf buf) {
            buf.writeInt(x); buf.writeInt(y); buf.writeInt(z);
            buf.writeString(material, MAX_BLOCK_STATE_CHARACTERS);
        }
    }

    /** Exact state used for ownership handoff or a sparse authority checkpoint. */
    public record AbilityTransfer(UUID player, long actionSequence, String abilityType,
                                  String world, boolean ownershipTransfer,
                                  int tempBlockOrdinal,
                                  double x, double y, double z,
                                  boolean hasDestination, double destinationX,
                                  double destinationY, double destinationZ,
                                  String state, double grabbedDistance,
                                  int animationCounter, int progressCounter,
                                  long predictionFrame,
                                  long elapsedMillis, long flightElapsedMillis,
                                  long delayElapsedMillis,
                                  List<AbilityTransferBlock> blocks) implements CustomPayload {
        public static final Id<AbilityTransfer> ID = id("ability_transfer");
        public static final PacketCodec<RegistryByteBuf, AbilityTransfer> CODEC =
                PacketCodec.of(AbilityTransfer::write, AbilityTransfer::new);

        private AbilityTransfer(final RegistryByteBuf buf) {
            this(buf.readUuid(), buf.readVarLong(), buf.readString(256), buf.readString(256),
                    buf.readBoolean(), buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readBoolean(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readString(64),
                    buf.readDouble(), buf.readInt(), buf.readInt(), buf.readVarLong(),
                    buf.readVarLong(), buf.readVarLong(), buf.readVarLong(), readTransferBlocks(buf));
        }

        private void write(final RegistryByteBuf buf) {
            buf.writeUuid(player); buf.writeVarLong(actionSequence); buf.writeString(abilityType, 256);
            buf.writeString(world, 256); buf.writeBoolean(ownershipTransfer);
            buf.writeVarInt(tempBlockOrdinal);
            buf.writeDouble(x); buf.writeDouble(y); buf.writeDouble(z);
            buf.writeBoolean(hasDestination); buf.writeDouble(destinationX);
            buf.writeDouble(destinationY); buf.writeDouble(destinationZ); buf.writeString(state, 64);
            buf.writeDouble(grabbedDistance); buf.writeInt(animationCounter); buf.writeInt(progressCounter);
            buf.writeVarLong(predictionFrame);
            buf.writeVarLong(elapsedMillis); buf.writeVarLong(flightElapsedMillis); buf.writeVarLong(delayElapsedMillis);
            buf.writeVarInt(blocks.size());
            blocks.forEach(block -> block.write(buf));
        }

        @Override public Id<AbilityTransfer> getId() { return ID; }
    }

    public static synchronized void registerTypes() {
        if (registered) return;
        registered = true;
        PayloadTypeRegistry.playC2S().register(ClientHello.ID, ClientHello.CODEC);
        PayloadTypeRegistry.playC2S().register(ClientReady.ID, ClientReady.CODEC);
        PayloadTypeRegistry.playC2S().register(InputVeto.ID, InputVeto.CODEC);
        PayloadTypeRegistry.playC2S().register(ActionTag.ID, ActionTag.CODEC);
        PayloadTypeRegistry.playC2S().register(HitClaim.ID, HitClaim.CODEC);
        PayloadTypeRegistry.playS2C().register(ServerWorldState.ID, ServerWorldState.CODEC);
        PayloadTypeRegistry.playS2C().registerLarge(ServerSnapshot.ID, ServerSnapshot.CODEC, 2 * 1024 * 1024);
        PayloadTypeRegistry.playS2C().register(NativeAction.ID, NativeAction.CODEC);
        PayloadTypeRegistry.playS2C().register(PlayerState.ID, PlayerState.CODEC);
        PayloadTypeRegistry.playS2C().register(StateDirective.ID, StateDirective.CODEC);
        PayloadTypeRegistry.playS2C().registerLarge(ConfigChunk.ID, ConfigChunk.CODEC, 2 * 1024 * 1024);
        PayloadTypeRegistry.playS2C().register(Reconcile.ID, Reconcile.CODEC);
        PayloadTypeRegistry.playS2C().registerLarge(TempBlockBatch.ID, TempBlockBatch.CODEC, 512 * 1024);
        PayloadTypeRegistry.playS2C().register(VelocityOwner.ID, VelocityOwner.CODEC);
        PayloadTypeRegistry.playS2C().register(VelocityOwnerV2.ID, VelocityOwnerV2.CODEC);
        PayloadTypeRegistry.playS2C().register(AbilityStateOwner.ID, AbilityStateOwner.CODEC);
        PayloadTypeRegistry.playS2C().register(TempFallingBlockPrepare.ID, TempFallingBlockPrepare.CODEC);
        PayloadTypeRegistry.playS2C().register(TempFallingBlockReceipt.ID, TempFallingBlockReceipt.CODEC);
        PayloadTypeRegistry.playS2C().register(DirectBlockReceipt.ID, DirectBlockReceipt.CODEC);
        PayloadTypeRegistry.playS2C().register(AbilityRemoved.ID, AbilityRemoved.CODEC);
        PayloadTypeRegistry.playS2C().register(AbilityTransfer.ID, AbilityTransfer.CODEC);
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

    private static List<AbilityTransferBlock> readTransferBlocks(final RegistryByteBuf buf) {
        final int size = bounded(buf.readVarInt(), 64, "ability transfer blocks");
        final List<AbilityTransferBlock> result = new ArrayList<>(size);
        for (int index = 0; index < size; index++) result.add(AbilityTransferBlock.read(buf));
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

    private static RegionProtectionAuthority.Snapshot readRegionProtection(final RegistryByteBuf buf) {
        final String world = buf.readString(256);
        final List<String> abilities = readStrings(buf, 63);
        final int count = bounded(buf.readVarInt(), 512, "region protection boxes");
        final List<RegionProtectionAuthority.Box> boxes = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            boxes.add(new RegionProtectionAuthority.Box(buf.readLong(),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt()));
        }
        return new RegionProtectionAuthority.Snapshot(world, abilities, boxes);
    }

    private static void writeRegionProtection(final RegistryByteBuf buf,
                                                final RegionProtectionAuthority.Snapshot snapshot) {
        final RegionProtectionAuthority.Snapshot safe = snapshot == null
                ? RegionProtectionAuthority.Snapshot.empty() : snapshot;
        buf.writeString(safe.world(), 256);
        writeStrings(buf, safe.abilities());
        buf.writeVarInt(safe.boxes().size());
        for (RegionProtectionAuthority.Box box : safe.boxes()) {
            buf.writeLong(box.abilityMask());
            buf.writeInt(box.minX()); buf.writeInt(box.minY()); buf.writeInt(box.minZ());
            buf.writeInt(box.maxX()); buf.writeInt(box.maxY()); buf.writeInt(box.maxZ());
        }
    }

    private static int bounded(int value, int maximum, String label) {
        if (value < 0 || value > maximum) throw new IllegalArgumentException("Invalid " + label + ": " + value);
        return value;
    }

    private static <T extends CustomPayload> CustomPayload.Id<T> id(String path) {
        return new CustomPayload.Id<>(Identifier.of("projectkorra", path));
    }
}

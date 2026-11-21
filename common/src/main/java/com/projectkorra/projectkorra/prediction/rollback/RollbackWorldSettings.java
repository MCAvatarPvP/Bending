package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detached startup rules/policy. Seeds initialize private streams, not the live world's running RNG. */
public record RollbackWorldSettings(long gameTime, long randomSeed, long soundSeed, int seaLevel, Difficulty difficulty,
                                    Policy policy, Map<String, Rule> rules, Map<String, Byte> legacyBlockData,
                                    Map<String, Integer> actorDespawnTime, ArrowLifetime arrowLifetime, RollbackStructurePieces structures,
                                    RollbackDuelInteractions interactions) {
    public RollbackWorldSettings(long gameTime, long randomSeed, long soundSeed, int seaLevel, Difficulty difficulty,
            Policy policy, Map<String, Rule> rules, Map<String, Byte> legacy, Map<String, Integer> despawn,
            ArrowLifetime arrows, RollbackStructurePieces structures) {
        this(gameTime, randomSeed, soundSeed, seaLevel, difficulty, policy, rules, legacy, despawn, arrows, structures, RollbackDuelInteractions.STANDARD);
    }

    public RollbackWorldSettings withInteractions(RollbackDuelInteractions rulesForMatch) {
        return new RollbackWorldSettings(gameTime, randomSeed, soundSeed, seaLevel, difficulty, policy, rules,
                legacyBlockData, actorDespawnTime, arrowLifetime, structures, rulesForMatch);
    }

    public record ArrowLifetime(int airGrace, int groundTicks, int creativeTicks, int unpickableTicks, int tridentTicks) {
        public static final ArrowLifetime DEFAULT = new ArrowLifetime(200, 1200, 1200, 1200, 1200);
        public ArrowLifetime {
            if (airGrace < -1 || groundTicks < 0 || creativeTicks < 0 || unpickableTicks < 0 || tridentTicks < 0) throw invalid("arrow lifespan");
        }
    }

    public RollbackWorldSettings(long gameTime, long randomSeed, long soundSeed, int seaLevel, Difficulty difficulty,
                                 Policy policy, Map<String, Rule> rules, Map<String, Byte> legacy,
                                 Map<String, Integer> despawn, ArrowLifetime arrows) {
        this(gameTime, randomSeed, soundSeed, seaLevel, difficulty, policy, rules, legacy, despawn, arrows, RollbackStructurePieces.EMPTY);
    }
    public RollbackWorldSettings withStructures(RollbackStructurePieces captured) {
        return new RollbackWorldSettings(gameTime, randomSeed, soundSeed, seaLevel, difficulty, policy, rules,
                legacyBlockData, actorDespawnTime, arrowLifetime, captured, interactions);
    }
    public RollbackWorldSettings(long gameTime, long randomSeed, long soundSeed, int seaLevel, Difficulty difficulty,
                                 Policy policy, Map<String, Rule> rules, Map<String, Byte> legacy, Map<String, Integer> despawn) {
        this(gameTime, randomSeed, soundSeed, seaLevel, difficulty, policy, rules, legacy, despawn, ArrowLifetime.DEFAULT);
    }

    public RollbackWorldSettings(long gameTime, long randomSeed, long soundSeed, int seaLevel, Difficulty difficulty,
                                 Policy policy, Map<String, Rule> rules, Map<String, Byte> legacyBlockData) {
        this(gameTime, randomSeed, soundSeed, seaLevel, difficulty, policy, rules, legacyBlockData, Map.of());
    }
    public RollbackWorldSettings(long gameTime, long randomSeed, long soundSeed, int seaLevel, Difficulty difficulty,
                                 Policy policy, Map<String, Rule> rules) {
        this(gameTime, randomSeed, soundSeed, seaLevel, difficulty, policy, rules, Map.of());
    }

    public static final int VERSION = 8, MAXIMUM_RULES = 1_024, MAXIMUM_BYTES = 524_288;
    public enum Difficulty { PEACEFUL, EASY, NORMAL, HARD }
    public enum Dimension { NORMAL, NETHER, THE_END }
    public sealed interface Rule permits Flag, IntegerRule { Object value(); }
    public record Flag(boolean enabled) implements Rule { @Override public Boolean value() { return enabled; } }
    public record IntegerRule(int number) implements Rule { @Override public Integer value() { return number; } }
    public record Policy(Dimension dimension, boolean pvp, boolean skipBlockedDamageTick, boolean updateEquipmentOnActions,
                         boolean nonPlayerScoreboards, boolean playerCramming, int maximumCollisions,
                         float jumpWalkingExhaustion, float jumpSprintingExhaustion, float regenerationExhaustion,
                         int containerUpdateRate, boolean parrotsStayOnShoulder,
                         boolean voidDamage, float voidDamageAmount, double voidDamageHeightOffset, OptionalInt netherCeilingHeight,
                         boolean splitOverstackedLoot, boolean alternativeLuckFormula,
                         boolean disablePlayerCrits, boolean disableSprintInterruptionOnAttack, float combatExhaustion) {
        public Policy(Dimension dimension, boolean pvp, boolean skipBlockedDamageTick, boolean updateEquipmentOnActions,
                      boolean nonPlayerScoreboards, boolean playerCramming, int maximumCollisions,
                      float jumpWalkingExhaustion, float jumpSprintingExhaustion, float regenerationExhaustion,
                      int containerUpdateRate, boolean parrotsStayOnShoulder, boolean voidDamage, float voidDamageAmount,
                      double voidDamageHeightOffset, OptionalInt netherCeilingHeight,
                      boolean splitOverstackedLoot, boolean alternativeLuckFormula) {
            this(dimension, pvp, skipBlockedDamageTick, updateEquipmentOnActions, nonPlayerScoreboards,
                    playerCramming, maximumCollisions, jumpWalkingExhaustion, jumpSprintingExhaustion, regenerationExhaustion,
                    containerUpdateRate, parrotsStayOnShoulder, voidDamage, voidDamageAmount, voidDamageHeightOffset,
                    netherCeilingHeight, splitOverstackedLoot, alternativeLuckFormula, false, false, .1F);
        }

        public Policy(Dimension dimension, boolean pvp, boolean skipBlockedDamageTick, boolean updateEquipmentOnActions,
                      boolean nonPlayerScoreboards, boolean playerCramming, int maximumCollisions,
                      float jumpWalkingExhaustion, float jumpSprintingExhaustion, float regenerationExhaustion,
                      int containerUpdateRate, boolean parrotsStayOnShoulder, boolean voidDamage, float voidDamageAmount,
                      double voidDamageHeightOffset, OptionalInt netherCeilingHeight) {
            this(dimension, pvp, skipBlockedDamageTick, updateEquipmentOnActions, nonPlayerScoreboards,
                    playerCramming, maximumCollisions, jumpWalkingExhaustion, jumpSprintingExhaustion,
                    regenerationExhaustion, containerUpdateRate, parrotsStayOnShoulder, voidDamage, voidDamageAmount,
                    voidDamageHeightOffset, netherCeilingHeight, true, false);
        }

        public Policy {
            Objects.requireNonNull(dimension); Objects.requireNonNull(netherCeilingHeight);
            if (maximumCollisions < 0 || maximumCollisions > 1_024 || !Double.isFinite(voidDamageHeightOffset)) throw invalid("policy bounds");
            for (float value : new float[]{jumpWalkingExhaustion, jumpSprintingExhaustion, regenerationExhaustion, voidDamageAmount, combatExhaustion}) {
                if (!Float.isFinite(value) || value < 0) throw invalid("policy amount");
            }
        }
    }
    public RollbackWorldSettings {
        Objects.requireNonNull(difficulty); Objects.requireNonNull(policy); Objects.requireNonNull(arrowLifetime); Objects.requireNonNull(structures); Objects.requireNonNull(interactions);
        if (actorDespawnTime.size() > 1_024) throw invalid("actor despawn count");
        actorDespawnTime = Collections.unmodifiableMap(new TreeMap<>(actorDespawnTime));
        actorDespawnTime.forEach((type, ticks) -> { key(type); if (ticks == null || ticks < 0) throw invalid("actor despawn ticks"); });
        if (legacyBlockData.size() > 65_536) throw invalid("legacy block count");
        legacyBlockData = Collections.unmodifiableMap(new TreeMap<>(legacyBlockData));
        legacyBlockData.forEach((state, value) -> {
            if (state.isEmpty() || state.length() > 1_024 || value == null || value == 0) throw invalid("legacy block data");
        });
        if (rules.isEmpty() || rules.size() > MAXIMUM_RULES) throw invalid("rule count");
        rules = Collections.unmodifiableMap(new TreeMap<>(rules));
        rules.forEach((key, value) -> { key(key); Objects.requireNonNull(value); });
    }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            boolean hasMatchRules = !interactions.equals(RollbackDuelInteractions.STANDARD);
            boolean hasStructures = hasMatchRules || !structures.pieces().isEmpty();
            boolean arrows = hasStructures || !arrowLifetime.equals(ArrowLifetime.DEFAULT);
            boolean despawn = arrows || !actorDespawnTime.isEmpty();
            boolean melee = despawn || policy.disablePlayerCrits() || policy.disableSprintInterruptionOnAttack() || policy.combatExhaustion() != .1F;
            boolean extended = melee || !legacyBlockData.isEmpty() || !policy.splitOverstackedLoot() || policy.alternativeLuckFormula();
            out.writeInt(hasMatchRules ? VERSION : hasStructures ? 6 : arrows ? 5 : despawn ? 4 : melee ? 3 : extended ? 2 : 1); out.writeLong(gameTime); out.writeLong(randomSeed); out.writeLong(soundSeed);
            out.writeInt(seaLevel); out.writeByte(difficulty.ordinal()); var p = policy;
            out.writeByte(p.dimension.ordinal()); out.writeBoolean(p.pvp); out.writeBoolean(p.skipBlockedDamageTick);
            out.writeBoolean(p.updateEquipmentOnActions); out.writeBoolean(p.nonPlayerScoreboards); out.writeBoolean(p.playerCramming);
            out.writeInt(p.maximumCollisions); out.writeFloat(p.jumpWalkingExhaustion); out.writeFloat(p.jumpSprintingExhaustion);
            out.writeFloat(p.regenerationExhaustion); out.writeInt(p.containerUpdateRate); out.writeBoolean(p.parrotsStayOnShoulder);
            out.writeBoolean(p.voidDamage); out.writeFloat(p.voidDamageAmount); out.writeDouble(p.voidDamageHeightOffset);
            out.writeBoolean(p.netherCeilingHeight.isPresent()); if (p.netherCeilingHeight.isPresent()) out.writeInt(p.netherCeilingHeight.getAsInt());
            if (extended) { out.writeBoolean(p.splitOverstackedLoot); out.writeBoolean(p.alternativeLuckFormula); }
            if (melee) {
                out.writeBoolean(p.disablePlayerCrits); out.writeBoolean(p.disableSprintInterruptionOnAttack); out.writeFloat(p.combatExhaustion);
            }
            out.writeInt(rules.size());
            for (var entry : rules.entrySet()) {
                var key = entry.getKey().getBytes(StandardCharsets.US_ASCII); out.writeShort(key.length); out.write(key);
                if (entry.getValue() instanceof Flag flag) { out.writeByte(0); out.writeBoolean(flag.enabled); }
                else { out.writeByte(1); out.writeInt(((IntegerRule) entry.getValue()).number); }
            }
            if (extended) {
                out.writeInt(legacyBlockData.size());
                String previous = "";
                for (var entry : legacyBlockData.entrySet()) {
                    String name = entry.getKey();
                    int shared = 0;
                    while (shared < previous.length() && shared < name.length() && previous.charAt(shared) == name.charAt(shared)) shared++;
                    out.writeShort(shared);
                    out.writeUTF(name.substring(shared));
                    out.writeByte(entry.getValue());
                    previous = name;
                }
            }
            if (despawn) {
                out.writeShort(actorDespawnTime.size());
                for (var entry : actorDespawnTime.entrySet()) { out.writeUTF(entry.getKey()); out.writeInt(entry.getValue()); }
            }
            if (arrows) {
                out.writeInt(arrowLifetime.airGrace()); out.writeInt(arrowLifetime.groundTicks());
                out.writeInt(arrowLifetime.creativeTicks()); out.writeInt(arrowLifetime.unpickableTicks()); out.writeInt(arrowLifetime.tridentTicks());
            }
            if (hasStructures) structures.write(out);
            if (hasMatchRules) interactions.write(out);
            if (bytes.size() > MAXIMUM_BYTES) throw invalid("wire budget");
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static RollbackWorldSettings decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes)); int version = in.readInt();
            if (version < 1 || version > VERSION) throw invalid("version");
            long time = in.readLong(), random = in.readLong(), sound = in.readLong(); int sea = in.readInt();
            var difficulty = choice(in, Difficulty.values()); var dimension = choice(in, Dimension.values());
            boolean pvp = bool(in), skip = bool(in), equipment = bool(in), scores = bool(in), cramming = bool(in);
            int collisions = in.readInt(); float walk = in.readFloat(), sprint = in.readFloat(), regeneration = in.readFloat();
            int container = in.readInt(); boolean parrots = bool(in), voidDamage = bool(in); float amount = in.readFloat(); double offset = in.readDouble();
            var ceiling = bool(in) ? OptionalInt.of(in.readInt()) : OptionalInt.empty();
            var policy = new Policy(dimension, pvp, skip, equipment, scores, cramming, collisions, walk, sprint, regeneration, container, parrots, voidDamage, amount, offset, ceiling,
                    version == 1 || bool(in), version >= 2 && bool(in), version >= 3 && bool(in),
                    version >= 3 && bool(in), version >= 3 ? in.readFloat() : .1F);
            int count = in.readInt(); if (count < 1 || count > MAXIMUM_RULES) throw invalid("rule count");
            var rules = new TreeMap<String, Rule>(); String previous = null;
            for (int i = 0; i < count; i++) {
                int length = in.readUnsignedShort(); if (length > 256 || length > in.available()) throw invalid("rule key length");
                String key = new String(in.readNBytes(length), StandardCharsets.US_ASCII); key(key);
                if (previous != null && previous.compareTo(key) >= 0) throw invalid("rule order/duplicate"); previous = key;
                var rule = switch (in.readUnsignedByte()) { case 0 -> new Flag(bool(in)); case 1 -> new IntegerRule(in.readInt()); default -> throw invalid("rule type"); };
                rules.put(key, rule);
            }
            var legacy = new TreeMap<String, Byte>();
            if (version >= 2) {
                int states = in.readInt();
                if (states < 0 || states > 65_536) throw invalid("legacy block count");
                previous = "";
                for (int i = 0; i < states; i++) {
                    int shared = in.readUnsignedShort();
                    if (shared > previous.length()) throw invalid("legacy block prefix");
                    String name = previous.substring(0, shared) + in.readUTF();
                    if (name.length() > 1_024 || previous.compareTo(name) >= 0) throw invalid("legacy block order/length");
                    legacy.put(name, in.readByte());
                    previous = name;
                }
            }
            var despawn = new TreeMap<String, Integer>();
            if (version >= 4) {
                int total = in.readUnsignedShort();
                if (total > 1_024) throw invalid("actor despawn count");
                previous = null;
                for (int i = 0; i < total; i++) {
                    String type = in.readUTF(); key(type);
                    if (previous != null && previous.compareTo(type) >= 0) throw invalid("actor despawn order");
                    previous = type; despawn.put(type, in.readInt());
                }
            }
            var arrows = version >= 5 ? new ArrowLifetime(in.readInt(), in.readInt(), in.readInt(), in.readInt(), in.readInt()) : ArrowLifetime.DEFAULT;
            var structures = version >= 6 ? RollbackStructurePieces.read(in) : RollbackStructurePieces.EMPTY;
            var interactions = version >= 7 ? RollbackDuelInteractions.read(in, version >= 8) : RollbackDuelInteractions.STANDARD;
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackWorldSettings(time, random, sound, sea, difficulty, policy, rules, legacy, despawn, arrows, structures, interactions);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid world settings: truncated data", failure); }
    }
    private static void key(String key) {
        if (key.length() > 256 || !key.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw invalid("rule key");
    }
    private static boolean bool(DataInputStream in) throws IOException { return switch (in.readUnsignedByte()) { case 0 -> false; case 1 -> true; default -> throw invalid("boolean"); }; }
    private static <T> T choice(DataInputStream in, T[] choices) throws IOException { int index = in.readUnsignedByte(); if (index >= choices.length) throw invalid("enum"); return choices[index]; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Invalid world settings: " + message); }
}

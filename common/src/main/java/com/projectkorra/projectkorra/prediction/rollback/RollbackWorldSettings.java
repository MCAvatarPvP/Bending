package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Detached startup rules/policy. Seeds initialize private streams, not the live world's running RNG. */
public record RollbackWorldSettings(long gameTime, long randomSeed, long soundSeed, int seaLevel, Difficulty difficulty,
                                    Policy policy, Map<String, Rule> rules) {
    public static final int VERSION = 1, MAXIMUM_RULES = 1_024, MAXIMUM_BYTES = 524_288;
    public enum Difficulty { PEACEFUL, EASY, NORMAL, HARD }
    public enum Dimension { NORMAL, NETHER, THE_END }
    public sealed interface Rule permits Flag, IntegerRule { Object value(); }
    public record Flag(boolean enabled) implements Rule { @Override public Boolean value() { return enabled; } }
    public record IntegerRule(int number) implements Rule { @Override public Integer value() { return number; } }
    public record Policy(Dimension dimension, boolean pvp, boolean skipBlockedDamageTick, boolean updateEquipmentOnActions,
                         boolean nonPlayerScoreboards, boolean playerCramming, int maximumCollisions,
                         float jumpWalkingExhaustion, float jumpSprintingExhaustion, float regenerationExhaustion,
                         int containerUpdateRate, boolean parrotsStayOnShoulder,
                         boolean voidDamage, float voidDamageAmount, double voidDamageHeightOffset, OptionalInt netherCeilingHeight) {
        public Policy {
            Objects.requireNonNull(dimension); Objects.requireNonNull(netherCeilingHeight);
            if (maximumCollisions < 0 || maximumCollisions > 1_024 || !Double.isFinite(voidDamageHeightOffset)) throw invalid("policy bounds");
            for (float value : new float[]{jumpWalkingExhaustion, jumpSprintingExhaustion, regenerationExhaustion, voidDamageAmount}) {
                if (!Float.isFinite(value) || value < 0) throw invalid("policy amount");
            }
        }
    }
    public RollbackWorldSettings {
        Objects.requireNonNull(difficulty); Objects.requireNonNull(policy);
        if (rules.isEmpty() || rules.size() > MAXIMUM_RULES) throw invalid("rule count");
        rules = Collections.unmodifiableMap(new TreeMap<>(rules));
        rules.forEach((key, value) -> { key(key); Objects.requireNonNull(value); });
    }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            out.writeInt(VERSION); out.writeLong(gameTime); out.writeLong(randomSeed); out.writeLong(soundSeed);
            out.writeInt(seaLevel); out.writeByte(difficulty.ordinal()); var p = policy;
            out.writeByte(p.dimension.ordinal()); out.writeBoolean(p.pvp); out.writeBoolean(p.skipBlockedDamageTick);
            out.writeBoolean(p.updateEquipmentOnActions); out.writeBoolean(p.nonPlayerScoreboards); out.writeBoolean(p.playerCramming);
            out.writeInt(p.maximumCollisions); out.writeFloat(p.jumpWalkingExhaustion); out.writeFloat(p.jumpSprintingExhaustion);
            out.writeFloat(p.regenerationExhaustion); out.writeInt(p.containerUpdateRate); out.writeBoolean(p.parrotsStayOnShoulder);
            out.writeBoolean(p.voidDamage); out.writeFloat(p.voidDamageAmount); out.writeDouble(p.voidDamageHeightOffset);
            out.writeBoolean(p.netherCeilingHeight.isPresent()); if (p.netherCeilingHeight.isPresent()) out.writeInt(p.netherCeilingHeight.getAsInt());
            out.writeInt(rules.size());
            for (var entry : rules.entrySet()) {
                var key = entry.getKey().getBytes(StandardCharsets.US_ASCII); out.writeShort(key.length); out.write(key);
                if (entry.getValue() instanceof Flag flag) { out.writeByte(0); out.writeBoolean(flag.enabled); }
                else { out.writeByte(1); out.writeInt(((IntegerRule) entry.getValue()).number); }
            }
            if (bytes.size() > MAXIMUM_BYTES) throw invalid("wire budget");
            return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static RollbackWorldSettings decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes)); if (in.readInt() != VERSION) throw invalid("version");
            long time = in.readLong(), random = in.readLong(), sound = in.readLong(); int sea = in.readInt();
            var difficulty = choice(in, Difficulty.values()); var dimension = choice(in, Dimension.values());
            boolean pvp = bool(in), skip = bool(in), equipment = bool(in), scores = bool(in), cramming = bool(in);
            int collisions = in.readInt(); float walk = in.readFloat(), sprint = in.readFloat(), regeneration = in.readFloat();
            int container = in.readInt(); boolean parrots = bool(in), voidDamage = bool(in); float amount = in.readFloat(); double offset = in.readDouble();
            var ceiling = bool(in) ? OptionalInt.of(in.readInt()) : OptionalInt.empty();
            var policy = new Policy(dimension, pvp, skip, equipment, scores, cramming, collisions, walk, sprint, regeneration, container, parrots, voidDamage, amount, offset, ceiling);
            int count = in.readInt(); if (count < 1 || count > MAXIMUM_RULES) throw invalid("rule count");
            var rules = new TreeMap<String, Rule>(); String previous = null;
            for (int i = 0; i < count; i++) {
                int length = in.readUnsignedShort(); if (length > 256 || length > in.available()) throw invalid("rule key length");
                String key = new String(in.readNBytes(length), StandardCharsets.US_ASCII); key(key);
                if (previous != null && previous.compareTo(key) >= 0) throw invalid("rule order/duplicate"); previous = key;
                var rule = switch (in.readUnsignedByte()) { case 0 -> new Flag(bool(in)); case 1 -> new IntegerRule(in.readInt()); default -> throw invalid("rule type"); };
                rules.put(key, rule);
            }
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackWorldSettings(time, random, sound, sea, difficulty, policy, rules);
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid world settings: truncated data", failure); }
    }
    private static void key(String key) {
        if (key.length() > 256 || !key.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw invalid("rule key");
    }
    private static boolean bool(DataInputStream in) throws IOException { return switch (in.readUnsignedByte()) { case 0 -> false; case 1 -> true; default -> throw invalid("boolean"); }; }
    private static <T> T choice(DataInputStream in, T[] choices) throws IOException { int index = in.readUnsignedByte(); if (index >= choices.length) throw invalid("enum"); return choices[index]; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Invalid world settings: " + message); }
}

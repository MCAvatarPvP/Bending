package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainCodec;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackLightSeed;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackTerrainSeed;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The same detached arena, clock, native policy and environment for both loaders. */
public record RollbackWorldSeed(UUID world, String name, int minimumY, int maximumY, long dayTime, boolean storm,
                                RollbackWorldSettings settings, RollbackBorderData border,
                                RollbackEnvironmentData environment, RollbackTerrainSeed terrain, RollbackLightSeed light) {
    public static final int VERSION = 2;
    public static final int MAXIMUM_BYTES = 134_217_728;
    private static final int MAXIMUM_NAME_BYTES = 1_024;

    /** Legacy capture/fixtures without native light; production world capture supplies both layers. */
    public RollbackWorldSeed(UUID world, String name, int minimumY, int maximumY, long dayTime, boolean storm,
            RollbackWorldSettings settings, RollbackBorderData border, RollbackEnvironmentData environment,
            RollbackTerrainSeed terrain) {
        this(world, name, minimumY, maximumY, dayTime, storm, settings, border, environment, terrain, null);
    }
    public RollbackLightSeed requireLight() {
        if (light == null) throw new IllegalStateException("World seed lacks captured native light layers");
        return light;
    }
    public RollbackWorldSeed {
        Objects.requireNonNull(world); Objects.requireNonNull(settings); Objects.requireNonNull(border);
        Objects.requireNonNull(environment); Objects.requireNonNull(terrain);
        if (name == null || name.isBlank()) throw invalid("world name");
        textBytes(name);
        // Native height/sky queries need the entire captured column, including air.
        if (minimumY >= maximumY || minimumY != terrain.bounds().minY() || maximumY != terrain.bounds().maxY()) {
            throw invalid("terrain must cover the world's full build height");
        }
        if (light != null && !light.bounds().equals(terrain.bounds())) throw invalid("light/terrain bounds differ");
        var bounds = terrain.bounds();
        long columns = ((long) Math.floorDiv(bounds.maxX() - 1, 16) - Math.floorDiv(bounds.minX(), 16) + 1)
                * ((long) Math.floorDiv(bounds.maxZ() - 1, 16) - Math.floorDiv(bounds.minZ(), 16) + 1);
        if (columns > 65_536) throw invalid("chunk budget");
    }

    public RollbackWorld.Identity identity() {
        return new RollbackWorld.Identity(name, RollbackWorld.Dimension.valueOf(settings.policy().dimension().name()), minimumY, maximumY);
    }

    /** Only the captured rectangle is visible; another match in a shared world is not a loaded region here. */
    public RollbackWorld.Conditions conditions() {
        var bounds = terrain.bounds(); var chunks = new HashSet<RollbackWorld.Chunk>();
        for (int x = Math.floorDiv(bounds.minX(), 16); x <= Math.floorDiv(bounds.maxX() - 1, 16); x++) {
            for (int z = Math.floorDiv(bounds.minZ(), 16); z <= Math.floorDiv(bounds.maxZ() - 1, 16); z++) {
                chunks.add(new RollbackWorld.Chunk(x, z));
            }
        }
        return new RollbackWorld.Conditions(Math.floorMod(dayTime, 24_000), dayTime, settings.difficulty().name(), storm, chunks);
    }

    public byte[] encode(RollbackTerrainCodec.BlockStates states, RollbackTerrainCodec.Limits limits) {
        var terrainBytes = RollbackTerrainCodec.encode(terrain, states, limits);
        byte[] lightBytes = light == null ? null : light.encode();
        var rules = settings.encode(); var borderBytes = border.encode(); var weather = environment.encode(); var nameBytes = textBytes(name);
        long size = 57L + nameBytes.length + rules.length + borderBytes.length + weather.length + terrainBytes.length + (lightBytes == null ? 0 : 4L + lightBytes.length);
        if (size > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var bytes = new ByteArrayOutputStream((int) size); var out = new DataOutputStream(bytes);
            out.writeInt(lightBytes == null ? 1 : VERSION); out.writeLong(world.getMostSignificantBits()); out.writeLong(world.getLeastSignificantBits());
            blob(out, nameBytes); out.writeInt(minimumY); out.writeInt(maximumY); out.writeLong(dayTime); out.writeBoolean(storm);
            blob(out, rules); blob(out, borderBytes); blob(out, weather); blob(out, terrainBytes);
            if (lightBytes != null) blob(out, lightBytes);
            if (bytes.size() > MAXIMUM_BYTES) throw invalid("wire budget");
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }

    public static RollbackWorldSeed decode(byte[] bytes, RollbackTerrainCodec.BlockStates states, RollbackTerrainCodec.Limits limits) {
        Objects.requireNonNull(bytes); Objects.requireNonNull(states); Objects.requireNonNull(limits);
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            int version = in.readInt();
            if (version != 1 && version != VERSION) throw invalid("version");
            var world = new UUID(in.readLong(), in.readLong());
            var name = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(blob(in, MAXIMUM_NAME_BYTES))).toString();
            int min = in.readInt(), max = in.readInt(); long day = in.readLong();
            boolean storm = switch (in.readUnsignedByte()) { case 0 -> false; case 1 -> true; default -> throw invalid("storm flag"); };
            var settings = RollbackWorldSettings.decode(blob(in, RollbackWorldSettings.MAXIMUM_BYTES));
            var border = RollbackBorderData.decode(blob(in, RollbackBorderData.MAXIMUM_BYTES));
            var environment = RollbackEnvironmentData.decode(blob(in, RollbackEnvironmentData.MAXIMUM_BYTES));
            var terrain = RollbackTerrainCodec.decode(blob(in, limits.maximumWireBytes()), states, limits);
            var light = version == 1 ? null : RollbackLightSeed.decode(blob(in, 28 + Math.min(limits.maximumCells(), 16_777_216)), limits.maximumCells());
            if (in.available() != 0) throw invalid("trailing data");
            return new RollbackWorldSeed(world, name, min, max, day, storm, settings, border, environment, terrain, light);
        } catch (IOException malformed) { throw new IllegalArgumentException("Invalid world seed: truncated/malformed data", malformed); }
    }

    private static void blob(DataOutputStream out, byte[] bytes) throws IOException { out.writeInt(bytes.length); out.write(bytes); }
    private static byte[] blob(DataInputStream in, int maximum) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > maximum || size > in.available()) throw invalid("component length");
        return in.readNBytes(size);
    }
    private static byte[] textBytes(String value) {
        if (value.length() > MAXIMUM_NAME_BYTES) throw invalid("name length");
        try {
            var buffer = StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
            if (buffer.remaining() > MAXIMUM_NAME_BYTES) throw invalid("name bytes");
            byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes); return bytes;
        } catch (java.nio.charset.CharacterCodingException malformed) { throw new IllegalArgumentException("Invalid world name", malformed); }
    }
    private static IllegalArgumentException invalid(String detail) { return new IllegalArgumentException("Invalid world seed: " + detail); }
}

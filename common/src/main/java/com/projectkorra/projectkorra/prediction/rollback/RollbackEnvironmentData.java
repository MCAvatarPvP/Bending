package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Initial native environment inputs; dimension/biome/timeline definitions belong to the agreed frozen registries. */
public record RollbackEnvironmentData(String dimensionType, boolean weatherEnabled, Weather weather) {
    public static final int VERSION = 1, MAXIMUM_BYTES = 512;
    /** Thunder is the native effective gradient (already multiplied by rain), not its internal raw strength. */
    public record Weather(float rain, float thunder) {
        public Weather {
            if (!Float.isFinite(rain) || !Float.isFinite(thunder) || rain < 0 || rain > 1 || thunder < 0 || thunder > rain) throw invalid("weather gradient");
        }
    }
    public RollbackEnvironmentData {
        Objects.requireNonNull(dimensionType); Objects.requireNonNull(weather);
        if (dimensionType.length() > 256 || !dimensionType.matches("[a-z0-9_.-]+:[a-z0-9/._-]+")) throw invalid("dimension key");
    }
    public byte[] encode() {
        try {
            var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
            byte[] key = dimensionType.getBytes(StandardCharsets.US_ASCII);
            out.writeInt(VERSION); out.writeShort(key.length); out.write(key); out.writeBoolean(weatherEnabled);
            out.writeFloat(weather.rain); out.writeFloat(weather.thunder); return bytes.toByteArray();
        } catch (IOException failure) { throw new IllegalStateException(failure); }
    }
    public static RollbackEnvironmentData decode(byte[] bytes) {
        if (bytes.length > MAXIMUM_BYTES) throw invalid("wire budget");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes)); if (in.readInt() != VERSION) throw invalid("version");
            int size = in.readUnsignedShort(); if (size > 256 || size > in.available()) throw invalid("dimension length");
            String key = new String(in.readNBytes(size), StandardCharsets.US_ASCII);
            boolean enabled = switch (in.readUnsignedByte()) { case 0 -> false; case 1 -> true; default -> throw invalid("boolean"); };
            var result = new RollbackEnvironmentData(key, enabled, new Weather(in.readFloat(), in.readFloat()));
            if (in.available() != 0) throw invalid("trailing data"); return result;
        } catch (IOException failure) { throw new IllegalArgumentException("Invalid environment: truncated data", failure); }
    }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException("Invalid environment: " + message); }
}

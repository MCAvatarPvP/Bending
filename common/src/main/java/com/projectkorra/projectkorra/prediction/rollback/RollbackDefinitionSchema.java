package com.projectkorra.projectkorra.prediction.rollback;

import java.io.*;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Canonical local definition values; no wire-selected classes or members are evaluated. */
public final class RollbackDefinitionSchema {
    private RollbackDefinitionSchema() { }

    public static String fingerprint(Object... values) {
        try {
            var bytes = new ByteArrayOutputStream();
            var output = new DataOutputStream(bytes);
            output.writeInt(values.length);
            for (Object value : values) write(output, Objects.requireNonNull(value));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | ReflectiveOperationException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Cannot describe local gameplay definition", error);
        }
    }

    private static void text(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length); out.write(bytes);
    }

    private static void write(DataOutputStream out, Object value) throws IOException, ReflectiveOperationException {
        if (value instanceof Annotation annotation) {
            out.writeByte(1); text(out, RollbackGraphCodec.Catalog.sharedTypeName(annotation.annotationType()));
            var members = annotation.annotationType().getDeclaredMethods();
            Arrays.sort(members, Comparator.comparing(java.lang.reflect.Method::getName));
            out.writeInt(members.length);
            for (var member : members) {
                if (member.getParameterCount() != 0 || !member.trySetAccessible())
                    throw new IllegalArgumentException("Inaccessible annotation member");
                text(out, member.getName()); write(out, member.invoke(annotation));
            }
        } else if (value instanceof Class<?> type) {
            out.writeByte(2); text(out, RollbackGraphCodec.Catalog.sharedTypeName(type));
        } else if (value instanceof Enum<?> constant) {
            out.writeByte(3); text(out, RollbackGraphCodec.Catalog.sharedTypeName(constant.getDeclaringClass())); text(out, constant.name());
        } else if (value.getClass().isArray()) {
            out.writeByte(4); text(out, RollbackGraphCodec.Catalog.sharedTypeName(value.getClass()));
            int size = Array.getLength(value); out.writeInt(size);
            for (int i = 0; i < size; i++) write(out, Array.get(value, i));
        } else if (value instanceof List<?> list) {
            out.writeByte(5); out.writeInt(list.size());
            for (Object item : list) write(out, Objects.requireNonNull(item));
        } else if (value instanceof String string) { out.writeByte(6); text(out, string);
        } else if (value instanceof Boolean item) { out.writeByte(7); out.writeBoolean(item);
        } else if (value instanceof Byte item) { out.writeByte(8); out.writeByte(item);
        } else if (value instanceof Short item) { out.writeByte(9); out.writeShort(item);
        } else if (value instanceof Integer item) { out.writeByte(10); out.writeInt(item);
        } else if (value instanceof Long item) { out.writeByte(11); out.writeLong(item);
        } else if (value instanceof Float item) { out.writeByte(12); out.writeInt(Float.floatToRawIntBits(item));
        } else if (value instanceof Double item) { out.writeByte(13); out.writeLong(Double.doubleToRawLongBits(item));
        } else if (value instanceof Character item) { out.writeByte(14); out.writeChar(item);
        } else throw new IllegalArgumentException("Unsupported definition value: " + value.getClass().getName());
    }
}

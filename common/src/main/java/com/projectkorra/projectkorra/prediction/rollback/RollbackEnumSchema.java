package com.projectkorra.projectkorra.prediction.rollback;

import java.io.IOException;
import java.util.*;
import net.bytebuddy.jar.asm.*;

/** Enum schema discovery without running unrelated gameplay static initializers. */
final class RollbackEnumSchema {
    private RollbackEnumSchema() { }
    private static final ClassValue<List<String>> NAMES = new ClassValue<>() {
        @Override protected List<String> computeValue(Class<?> type) {
            if (!type.isEnum()) throw new IllegalArgumentException("Not an enum");
            var names = new ArrayList<String>();
            String resource = "/" + type.getName().replace('.', '/') + ".class";
            try (var input = Objects.requireNonNull(type.getResourceAsStream(resource), "Missing local enum bytecode")) {
                new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                        if ((access & Opcodes.ACC_ENUM) != 0) names.add(name);
                        return null;
                    }
                }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            } catch (IOException error) { throw new IllegalStateException("Cannot read local enum schema", error); }
            return List.copyOf(names);
        }
    };
    // EnumMap has no public key-type accessor. Read only this fixed JDK field;
    // probing candidate constants would initialize unrelated gameplay enums.
    private static final class EnumMapType {
        static final sun.misc.Unsafe ACCESS;
        static final long OFFSET;
        static {
            try {
                var singleton = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
                singleton.setAccessible(true);
                ACCESS = (sun.misc.Unsafe) singleton.get(null);
                var keyType = EnumMap.class.getDeclaredField("keyType");
                if (keyType.getType() != Class.class) throw new IllegalStateException("Unsupported EnumMap layout");
                OFFSET = ACCESS.objectFieldOffset(keyType);
            } catch (ReflectiveOperationException error) {
                throw new ExceptionInInitializerError(error);
            }
        }
    }
    static Class<?> keyType(EnumMap<?, ?> map) {
        Object type = EnumMapType.ACCESS.getObject(Objects.requireNonNull(map), EnumMapType.OFFSET);
        if (!(type instanceof Class<?> keyType) || !keyType.isEnum())
            throw new IllegalArgumentException("Invalid EnumMap key type");
        return keyType;
    }
    static List<String> names(Class<?> type) { return NAMES.get(type); }
    static Object[] constants(Class<?> type) {
        Object[] values = type.getEnumConstants();
        var names = names(type);
        if (values.length != names.size()) throw new IllegalArgumentException("Enum schema differs from its local bytecode");
        for (int i = 0; i < values.length; i++) if (!((Enum<?>) values[i]).name().equals(names.get(i)))
            throw new IllegalArgumentException("Enum ordinal differs from its local declaration order");
        return values;
    }
}

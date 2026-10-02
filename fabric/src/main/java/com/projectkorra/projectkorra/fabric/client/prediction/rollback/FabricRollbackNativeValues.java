package com.projectkorra.projectkorra.fabric.client.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.EntityAttachments;
import net.minecraft.entity.EntityAttachmentType;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.*;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Box;

/** Fixed value conversion only; no native entity, world or service handle can enter the wire. */
final class FabricRollbackNativeValues {
    private static final MethodHandle POINTS, ATTACHMENT_FACTORY;
    static {
        try {
            var lookup = MethodHandles.privateLookupIn(EntityAttachments.class, MethodHandles.lookup());
            POINTS = lookup.findGetter(EntityAttachments.class, net.fabricmc.loader.api.FabricLoader.getInstance().getMappingResolver().mapFieldName("intermediary", "net.minecraft.class_9066", "field_47752", "Ljava/util/Map;"), Map.class);
            ATTACHMENT_FACTORY = lookup.findConstructor(EntityAttachments.class, MethodType.methodType(void.class, Map.class));
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private FabricRollbackNativeValues() { }

    static Kind kind(Class<?> type) {
        if (type.isPrimitive() && type != void.class) return Kind.valueOf(type.getName().toUpperCase(Locale.ROOT));
        if (type.isEnum()) return Kind.ENUM;
        if (type == Vec3d.class) return Kind.VECTOR;
        if (type == net.minecraft.util.math.Box.class) return Kind.BOX;
        if (type == BlockPos.class) return Kind.BLOCK;
        if (type == ChunkPos.class) return Kind.CHUNK;
        if (type == EntityDimensions.class) return Kind.DIMENSIONS;
        if (type == Optional.class) return Kind.OPTIONAL_BLOCK;
        throw new IllegalArgumentException("Unsupported player value type " + type.getName());
    }
    @SuppressWarnings("unchecked")
    static Cell encode(Class<?> type, Object value) {
        Kind kind = kind(type);
        if (value == null) return new Cell(kind, null);
        Object result = switch (kind) {
            case ENUM -> ((Enum<?>) value).name();
            case VECTOR -> vector((Vec3d) value);
            case BOX -> { var b = (net.minecraft.util.math.Box) value; yield new Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ); }
            case BLOCK -> block((BlockPos) value);
            case CHUNK -> { var p = (ChunkPos) value; yield new Chunk(p.x, p.z); }
            case OPTIONAL_BLOCK -> ((Optional<BlockPos>) value).map(FabricRollbackNativeValues::block);
            case DIMENSIONS -> {
                var d = (EntityDimensions) value;
                var points = new TreeMap<String, List<Vector>>();
                try {
                    Map<EntityAttachmentType, List<Vec3d>> nativePoints = (Map<EntityAttachmentType, List<Vec3d>>) POINTS.invoke(d.attachments());
                    nativePoints.forEach((key, vectors) -> points.put(key.name(), vectors.stream().map(FabricRollbackNativeValues::vector).toList()));
                } catch (Throwable failure) { throw failed(failure); }
                yield new Dimensions(d.width(), d.height(), d.eyeHeight(), d.fixed(), points);
            }
            default -> value;
        };
        return new Cell(kind, result);
    }
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object decode(Class<?> type, Cell cell) {
        if (cell == null || cell.kind() != kind(type)) throw new IllegalArgumentException("Player value schema changed for " + type.getName());
        Object value = cell.value();
        if (value == null) return null;
        return switch (cell.kind()) {
            case ENUM -> Enum.valueOf((Class) type, (String) value);
            case VECTOR -> vector((Vector) value);
            case BOX -> { var b = (Box) value; yield new net.minecraft.util.math.Box(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()); }
            case BLOCK -> block((Block) value);
            case CHUNK -> { var p = (Chunk) value; yield new ChunkPos(p.x(), p.z()); }
            case OPTIONAL_BLOCK -> ((Optional<Block>) value).map(FabricRollbackNativeValues::block);
            case DIMENSIONS -> {
                var d = (Dimensions) value;
                var points = new EnumMap<EntityAttachmentType, List<Vec3d>>(EntityAttachmentType.class);
                d.attachments().forEach((key, vectors) -> points.put(EntityAttachmentType.valueOf(key), vectors.stream().map(FabricRollbackNativeValues::vector).toList()));
                if (points.size() != EntityAttachmentType.values().length) throw new IllegalArgumentException("Attachment schema changed");
                try {
                    var attachments = (EntityAttachments) ATTACHMENT_FACTORY.invoke(Map.copyOf(points));
                    yield new EntityDimensions(d.width(), d.height(), d.eyeHeight(), attachments, d.fixed());
                } catch (Throwable failure) { throw failed(failure); }
            }
            default -> value;
        };
    }
    private static Vector vector(Vec3d value) { return new Vector(value.x, value.y, value.z); }
    private static Vec3d vector(Vector value) { return new Vec3d(value.x(), value.y(), value.z()); }
    private static Block block(BlockPos value) { return new Block(value.getX(), value.getY(), value.getZ()); }
    private static BlockPos block(Block value) { return new BlockPos(value.x(), value.y(), value.z()); }
    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Native player value conversion failed", failure);
    }
}

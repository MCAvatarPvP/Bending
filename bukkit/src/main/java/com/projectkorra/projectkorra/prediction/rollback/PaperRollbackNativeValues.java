package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityAttachments;
import net.minecraft.world.entity.EntityAttachment;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.*;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Vector;
import com.projectkorra.projectkorra.prediction.rollback.RollbackPlayerValues.Box;

/** Fixed value conversion only; no native entity, world or service handle can enter the wire. */
final class PaperRollbackNativeValues {
    private static final MethodHandle POINTS, ATTACHMENT_FACTORY;
    static {
        try {
            var lookup = MethodHandles.privateLookupIn(EntityAttachments.class, MethodHandles.lookup());
            POINTS = lookup.findGetter(EntityAttachments.class, "attachments", Map.class);
            ATTACHMENT_FACTORY = lookup.findConstructor(EntityAttachments.class, MethodType.methodType(void.class, Map.class));
        } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
    }
    private PaperRollbackNativeValues() { }

    static Kind kind(Class<?> type) {
        if (type.isPrimitive() && type != void.class) return Kind.valueOf(type.getName().toUpperCase(Locale.ROOT));
        if (type.isEnum()) return Kind.ENUM;
        if (type == Vec3.class) return Kind.VECTOR;
        if (type == AABB.class) return Kind.BOX;
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
            case VECTOR -> vector((Vec3) value);
            case BOX -> { var b = (AABB) value; yield new Box(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ); }
            case BLOCK -> block((BlockPos) value);
            case CHUNK -> { var p = (ChunkPos) value; yield new Chunk(p.x, p.z); }
            case OPTIONAL_BLOCK -> ((Optional<BlockPos>) value).map(PaperRollbackNativeValues::block);
            case DIMENSIONS -> {
                var d = (EntityDimensions) value;
                var points = new TreeMap<String, List<Vector>>();
                try {
                    Map<EntityAttachment, List<Vec3>> nativePoints = (Map<EntityAttachment, List<Vec3>>) POINTS.invoke(d.attachments());
                    nativePoints.forEach((key, vectors) -> points.put(key.name(), vectors.stream().map(PaperRollbackNativeValues::vector).toList()));
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
            case BOX -> { var b = (Box) value; yield new AABB(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()); }
            case BLOCK -> block((Block) value);
            case CHUNK -> { var p = (Chunk) value; yield new ChunkPos(p.x(), p.z()); }
            case OPTIONAL_BLOCK -> ((Optional<Block>) value).map(PaperRollbackNativeValues::block);
            case DIMENSIONS -> {
                var d = (Dimensions) value;
                var points = new EnumMap<EntityAttachment, List<Vec3>>(EntityAttachment.class);
                d.attachments().forEach((key, vectors) -> points.put(EntityAttachment.valueOf(key), vectors.stream().map(PaperRollbackNativeValues::vector).toList()));
                if (points.size() != EntityAttachment.values().length) throw new IllegalArgumentException("Attachment schema changed");
                try {
                    var attachments = (EntityAttachments) ATTACHMENT_FACTORY.invoke(Map.copyOf(points));
                    yield new EntityDimensions(d.width(), d.height(), d.eyeHeight(), attachments, d.fixed());
                } catch (Throwable failure) { throw failed(failure); }
            }
            default -> value;
        };
    }
    private static Vector vector(Vec3 value) { return new Vector(value.x, value.y, value.z); }
    private static Vec3 vector(Vector value) { return new Vec3(value.x(), value.y(), value.z()); }
    private static Block block(BlockPos value) { return new Block(value.getX(), value.getY(), value.getZ()); }
    private static BlockPos block(Block value) { return new BlockPos(value.x(), value.y(), value.z()); }
    private static RuntimeException failed(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Native player value conversion failed", failure);
    }
}

package com.projectkorra.projectkorra.prediction.rollback;

import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.FluidCollisionMode;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackPlayer;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackWorld;
import java.io.*;
import java.util.Objects;
import java.util.function.Predicate;

/** A requested native interaction target. The private world resolves identity, reach and geometry for the replayed tick. */
public sealed interface RollbackInteractionTarget {
    enum Face { DOWN, UP, NORTH, SOUTH, WEST, EAST }
    record Offset(float x, float y, float z) {
        public Offset {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) throw new IllegalArgumentException("Invalid hit offset");
        }
    }
    record Block(int x, int y, int z, Face face, Offset hit, boolean inside) implements RollbackInteractionTarget {
        public Block {
            Objects.requireNonNull(face); Objects.requireNonNull(hit);
            if (Math.abs((long)x) > 30_000_000 || Math.abs((long)z) > 30_000_000 || y < -2048 || y > 2047
                    || !component(hit.x) || !component(hit.y) || !component(hit.z)) throw new IllegalArgumentException("Invalid block interaction target");
        }
        private static boolean component(float value) { return value >= -.500001F && value <= 1.500001F; }
    }
    record Actor(int networkId, Offset hit) implements RollbackInteractionTarget {
        public Actor {
            if (hit != null && (Math.abs(hit.x) > 128 || Math.abs(hit.y) > 128 || Math.abs(hit.z) > 128))
                throw new IllegalArgumentException("Invalid actor hit offset");
        }
    }
    static void validate(RollbackInputActions.Kind kind, RollbackInteractionTarget target) {
        boolean valid = switch (kind) {
            case RIGHT_CLICK_BLOCK -> target instanceof Block;
            case RIGHT_CLICK_ENTITY -> target instanceof Actor actor && actor.hit != null;
            case ATTACK, INTERACT_ENTITY -> target instanceof Actor actor && actor.hit == null;
            default -> target == null;
        };
        if (!valid) throw new IllegalArgumentException("Interaction target differs from action");
    }
    /** Verify the replayed eye, aim, native interaction range, selected face and current target bounds. */
    static boolean allowed(RollbackPlayer player, RollbackPlayerInput.Edge edge, double range) {
        return allowed(player, edge, range, entity -> true);
    }
    static boolean allowed(RollbackPlayer player, RollbackPlayerInput.Edge edge, double range, Predicate<Entity> pickable) {
        var target = edge.action().target();
        if (target == null) return true;
        if (!Double.isFinite(range) || range <= 0 || range > 128) return false;
        var world = (RollbackWorld) player.getWorld();
        var eye = player.getEyeLocation();
        eye.setYaw(edge.yaw());
        eye.setPitch(edge.pitch());
        var dir = eye.getDirection();
        Predicate<Entity> otherTarget = candidate -> !candidate.getUniqueId().equals(player.getUniqueId()) && pickable.test(candidate);
        if (target instanceof Block block) {
            var pos = new RollbackBlockStore.Position(block.x, block.y, block.z);
            if (!world.terrain().bounds().contains(pos)) return false;
            var point = new Vector((double) block.x + block.hit.x, (double) block.y + block.hit.y, (double) block.z + block.hit.z);
            double distance = point.clone().subtract(eye.toVector()).length();
            if (distance > range + 1.0E-4) return false;
            double limit = Math.min(range, distance + .05);
            var hit = world.traceBlocks(eye, dir, limit, FluidCollisionMode.NEVER, false, player);
            if (hit == null || !hit.block().equals(pos) || !hit.face().name().equals(block.face.name())
                    || hit.inside() != block.inside || hit.result().getHitPosition().subtract(point).lengthSquared() > .0025) return false;
            var closest = world.rayTrace(eye, dir, limit, FluidCollisionMode.NEVER, false, 0, otherTarget);
            return closest == null || closest.getHitEntity() == null;
        }
        var actor = (Actor) target;
        var entity = world.entities().get(actor.networkId);
        if (entity == null || !otherTarget.test(entity)) return false;
        var hit = world.rayTrace(eye, dir, range, FluidCollisionMode.NEVER, false, 0, otherTarget);
        if (hit == null || hit.getHitEntity() == null || !hit.getHitEntity().getUniqueId().equals(entity.getUniqueId())) return false;
        if (actor.hit == null) return true;
        var pos = entity.getLocation();
        var point = new Vector(pos.getX() + actor.hit.x, pos.getY() + actor.hit.y, pos.getZ() + actor.hit.z);
        if (hit.getHitPosition().subtract(point).lengthSquared() <= .0025) return true;
        // Vanilla may pick the eye itself when the player starts inside the target.
        var bounds = entity.getBoundingBox();
        return eye.getX() >= bounds.getMinX() && eye.getX() <= bounds.getMaxX()
                && eye.getY() >= bounds.getMinY() && eye.getY() <= bounds.getMaxY()
                && eye.getZ() >= bounds.getMinZ() && eye.getZ() <= bounds.getMaxZ()
                && eye.toVector().subtract(point).lengthSquared() <= .0025;
    }

    static int size(RollbackInteractionTarget target) {
        return target instanceof Block ? 27 : target instanceof Actor a ? (a.hit == null ? 5 : 17) : 1;
    }
    static void write(DataOutput out, RollbackInteractionTarget target) throws IOException {
        if (target == null) { out.writeByte(0); return; }
        if (target instanceof Actor a) {
            out.writeByte(a.hit == null ? 1 : 2); out.writeInt(a.networkId);
            if (a.hit != null) offset(out, a.hit); return;
        }
        var b = (Block) target;
        out.writeByte(3); out.writeInt(b.x); out.writeInt(b.y); out.writeInt(b.z); out.writeByte(b.face.ordinal()); offset(out, b.hit); out.writeBoolean(b.inside);
    }
    static RollbackInteractionTarget read(DataInput in) throws IOException {
        return switch (in.readUnsignedByte()) {
            case 0 -> null;
            case 1 -> new Actor(in.readInt(), null);
            case 2 -> new Actor(in.readInt(), offset(in));
            case 3 -> {
                int x = in.readInt(), y = in.readInt(), z = in.readInt(), face = in.readUnsignedByte();
                if (face >= Face.values().length) throw new IllegalArgumentException("Unknown interaction face");
                var offset = offset(in); int inside = in.readUnsignedByte();
                if (inside > 1) throw new IllegalArgumentException("Invalid inside-block flag");
                yield new Block(x, y, z, Face.values()[face], offset, inside != 0);
            }
            default -> throw new IllegalArgumentException("Unknown interaction target");
        };
    }
    private static void offset(DataOutput out, Offset p) throws IOException { out.writeFloat(p.x); out.writeFloat(p.y); out.writeFloat(p.z); }
    private static Offset offset(DataInput in) throws IOException { return new Offset(in.readFloat(), in.readFloat(), in.readFloat()); }
}

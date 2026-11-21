package com.projectkorra.projectkorra.prediction.rollback.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackEntityBody.Motion;

/**
 * Minecraft's collision/step selection over a supplied logical collider source.
 * Native backends perform clipping and collect candidate step heights. This returns
 * displacement only: it does not replace travel, gravity, fluid physics, fall effects,
 * collision callbacks or velocity updates with an alternate movement implementation.
 * Native shapes are ephemeral query values, never stored in a rollback checkpoint.
 */
public final class RollbackMovementSolver<S> {
    public record Input(Box bounds, Motion requested, float stepHeight, boolean onGround) {
        public Input {
            Objects.requireNonNull(bounds, "bounds"); Objects.requireNonNull(requested, "requested");
            if (!Float.isFinite(stepHeight) || stepHeight < 0) throw new IllegalArgumentException("Step height");
        }
    }
    public record Result(Motion displacement, boolean horizontalCollision, boolean verticalCollision,
                         boolean collisionBelow, boolean stepped) { }
    public record Colliders<S>(List<S> shapes, List<Box> boxes) {
        public Colliders { shapes = List.copyOf(shapes); boxes = List.copyOf(boxes); }
        public int size() { return Math.addExact(shapes.size(), boxes.size()); }
    }
    public record Limits(int colliders, int stepCandidates, long collisionWork) {
        public Limits {
            if (colliders < 1 || stepCandidates < 1 || collisionWork < 1) throw new IllegalArgumentException("Movement query limits");
        }
        public static Limits standard() { return new Limits(65_536, 256, 4_194_304); }
    }
    /** Includes entity eligibility, pose-dependent block contexts and the captured world border. */
    public interface Source<S> {
        List<Box> entities(Box sweptBounds);
        Colliders<S> terrainAndBorder(Box sweptBounds);
    }
    public interface Native<S> {
        Motion clip(Box bounds, Motion requested, Colliders<S> colliders);
        float[] stepHeights(Box bounds, Colliders<S> colliders, float maximum, float previousY);
    }

    private final Native<S> backend;
    private final Limits limits;
    private final Thread owner = Thread.currentThread();
    public RollbackMovementSolver(Native<S> backend, Limits limits) {
        this.backend = Objects.requireNonNull(backend, "backend"); this.limits = Objects.requireNonNull(limits, "limits");
    }

    public Result resolve(Input input, Source<S> source) {
        if (owner != Thread.currentThread()) throw new IllegalStateException("Movement solver crossed threads");
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(source, "source");
        Motion requested = input.requested;
        if (requested.x() == 0 && requested.y() == 0 && requested.z() == 0) return result(requested, requested, false);
        Box swept = stretch(input.bounds, requested);
        List<Box> entities = List.copyOf(source.entities(swept));
        if (entities.size() > limits.colliders) throw new IllegalStateException("Movement entity collider budget exceeded");
        Colliders<S> initial = combine(source.terrainAndBorder(swept), entities, false);
        long work = charge(0, initial);
        Motion clipped = Objects.requireNonNull(backend.clip(input.bounds, requested, initial), "clipped movement");
        boolean landing = requested.y() != clipped.y() && requested.y() < 0;
        boolean obstructed = requested.x() != clipped.x() || requested.z() != clipped.z();
        if (input.stepHeight <= 0 || !(landing || input.onGround) || !obstructed) return result(requested, clipped, false);

        Box stepStart = landing ? move(input.bounds, new Motion(0, clipped.y(), 0)) : input.bounds;
        Box stepRegion = stretch(stepStart, new Motion(requested.x(), input.stepHeight, requested.z()));
        // Native Entity movement includes the supporting surface when already grounded.
        if (!landing) stepRegion = stretch(stepRegion, new Motion(0, -(double) 1e-5F, 0));
        Colliders<S> step = combine(source.terrainAndBorder(stepRegion), entities, true);
        work = charge(work, step);
        float[] heights = Objects.requireNonNull(backend.stepHeights(stepStart, step, input.stepHeight, (float) clipped.y()), "step heights").clone();
        if (heights.length > limits.stepCandidates) throw new IllegalStateException("Movement step candidate budget exceeded");
        float previous = -Float.MAX_VALUE;
        for (float height : heights) {
            if (!Float.isFinite(height) || height < 0 || height > input.stepHeight || Float.compare(height, previous) <= 0 || height == (float) clipped.y()) {
                throw new IllegalStateException("Native step candidate contract violated");
            }
            previous = height;
            work = charge(work, step);
            Motion stepped = Objects.requireNonNull(backend.clip(stepStart, new Motion(requested.x(), height, requested.z()), step), "stepped movement");
            if (horizontal(stepped) > horizontal(clipped)) {
                return result(requested, new Motion(stepped.x(), stepped.y() + (stepStart.minY() - input.bounds.minY()), stepped.z()), true);
            }
        }
        return result(requested, clipped, false);
    }

    private Colliders<S> combine(Colliders<S> world, List<Box> entities, boolean entitiesFirst) {
        Objects.requireNonNull(world, "world colliders");
        if ((long) world.size() + entities.size() > limits.colliders) throw new IllegalStateException("Movement collider budget exceeded");
        List<Box> boxes = new ArrayList<>(world.boxes.size() + entities.size());
        if (entitiesFirst) { boxes.addAll(entities); boxes.addAll(world.boxes); }
        else { boxes.addAll(world.boxes); boxes.addAll(entities); }
        return new Colliders<>(world.shapes, boxes);
    }
    private long charge(long work, Colliders<S> colliders) {
        long next = Math.addExact(work, Math.max(1, colliders.size()));
        if (next > limits.collisionWork) throw new IllegalStateException("Movement collision work budget exceeded");
        return next;
    }
    private static Result result(Motion requested, Motion actual, boolean stepped) {
        boolean vertical = requested.y() != actual.y();
        return new Result(actual, requested.x() != actual.x() || requested.z() != actual.z(), vertical, vertical && requested.y() < 0, stepped);
    }
    private static double horizontal(Motion value) { return value.x() * value.x() + value.z() * value.z(); }
    public static Box move(Box box, Motion delta) {
        return new Box(box.minX() + delta.x(), box.minY() + delta.y(), box.minZ() + delta.z(),
                box.maxX() + delta.x(), box.maxY() + delta.y(), box.maxZ() + delta.z());
    }
    public static Box stretch(Box box, Motion delta) {
        return new Box(box.minX() + Math.min(0, delta.x()), box.minY() + Math.min(0, delta.y()), box.minZ() + Math.min(0, delta.z()),
                box.maxX() + Math.max(0, delta.x()), box.maxY() + Math.max(0, delta.y()), box.maxZ() + Math.max(0, delta.z()));
    }
    /** Bounds the native block iterator before it constructs its inclusive cursor and chunk indices. */
    public static void requireBlockQuery(Box bounds) {
        double[] minimum = {bounds.minX(), bounds.minY(), bounds.minZ()}, maximum = {bounds.maxX(), bounds.maxY(), bounds.maxZ()};
        long volume = 1;
        for (int axis = 0; axis < 3; axis++) {
            if (minimum[axis] < Integer.MIN_VALUE + 1_024.0 || maximum[axis] > Integer.MAX_VALUE - 1_024.0) {
                throw new IllegalArgumentException("Movement query outside native coordinates");
            }
            long low = (long) Math.floor(minimum[axis] - 1e-7) - 1;
            long high = (long) Math.floor(maximum[axis] + 1e-7) + 1;
            long width = high - low + 1;
            if (width > RollbackBlockRay.MAX_VISITED_BLOCKS || volume > RollbackBlockRay.MAX_VISITED_BLOCKS / width) {
                throw new IllegalStateException("Movement block query budget exceeded");
            }
            volume *= width;
        }
    }
}

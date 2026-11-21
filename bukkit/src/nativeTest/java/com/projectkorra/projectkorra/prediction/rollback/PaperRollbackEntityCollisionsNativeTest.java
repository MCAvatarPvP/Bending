package com.projectkorra.projectkorra.prediction.rollback;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import ca.spottedleaf.moonrise.patches.collisions.shape.CollisionVoxelShape;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;
import com.projectkorra.projectkorra.prediction.rollback.world.RollbackVoxelIntersections;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** The installed Paper implementation supplies expectations; no duplicated collision formula is the oracle. */
class PaperRollbackEntityCollisionsNativeTest {
    @BeforeAll static void bootstrap() { PaperRollbackDamageNativeTest.bootstrap(); }

    @Test void clientVoxelKernelMatchesPaperForNativeShapesAndBoundaryContacts() {
        var shapes = List.of(Shapes.empty(), Shapes.block(), Shapes.box(.125, .25, .125, .875, .75, .875),
                Shapes.or(Shapes.box(0, 0, 0, .25, 1, 1), Shapes.box(.75, 0, 0, 1, 1, 1)),
                Shapes.join(Shapes.block(), Shapes.box(.25, .25, .25, .75, .75, .75), BooleanOp.ONLY_FIRST));
        var random = new Random(21863312);
        for (var raw : shapes) for (double offset : new double[]{0, .12345678, -9.125}) {
            var shape = raw.move(offset, .125, -.25); var grid = grid(shape);
            assertEquals(((CollisionVoxelShape) shape).moonrise$getSingleAABBRepresentation() != null, RollbackVoxelIntersections.single(grid));
            for (int sample = 0; sample < 1_000; sample++) {
                double x = offset + random.nextDouble(-.5, 1.5), y = random.nextDouble(-.5, 1.5), z = random.nextDouble(-.75, 1.25);
                compare(shape, grid, new AABB(x, y, z, x + random.nextDouble(0, 1), y + random.nextDouble(0, 1), z + random.nextDouble(0, 1)));
            }
            for (int axis = 0; axis < 3; axis++) for (int i = 0; i <= grid.size(axis); i++) {
                double edge = grid.coordinate(axis, i);
                for (double delta : new double[]{-2e-7, -1e-7, -5e-8, 0, 5e-8, 1e-7, 2e-7}) {
                    double[] min = {offset - .5, -.5, -.75}, max = {offset + 1.5, 1.5, 1.25};
                    min[axis] = edge + delta;
                    compare(shape, grid, new AABB(min[0], min[1], min[2], max[0], max[1], max[2]));
                    min[axis] = edge - 1; max[axis] = edge + delta;
                    compare(shape, grid, new AABB(min[0], min[1], min[2], max[0], max[1], max[2]));
                }
            }
        }
    }

    @Test void narrowCollisionBoxesUsePapersPerAxisEmptinessRule() {
        for (double width : new double[]{0, 5e-8, 1e-7, 2e-7, 1}) {
            compareEmpty(new AABB(0, 0, 0, width, 2, 3));
            compareEmpty(new AABB(0, 0, 0, 2, width, 3));
            compareEmpty(new AABB(0, 0, 0, 2, 3, width));
        }
    }
    @Test void nativeHardCollisionReferenceExcludesGrazingAndFlatBoxes() throws Exception {
        PaperRollbackWorldAccessNativeTest.onTickThread(() -> {
            var world = new PaperRollbackWorldAccess(new PaperRollbackWorldAccessNativeTest.Queries(), 4);
            var body = new net.minecraft.world.entity.player.Player(world.world(), new com.mojang.authlib.GameProfile(new java.util.UUID(0, 7), "collider")) {
                @Override public net.minecraft.world.level.GameType gameMode() { return net.minecraft.world.level.GameType.SURVIVAL; }
                @Override public boolean canBeCollidedWith(net.minecraft.world.entity.Entity entity) { return true; }
            };
            var entities = new net.minecraft.world.level.EntityGetter() {
                @Override public List<net.minecraft.world.entity.Entity> getEntities(net.minecraft.world.entity.Entity except, AABB box,
                        java.util.function.Predicate<? super net.minecraft.world.entity.Entity> predicate) {
                    return body != except && body.getBoundingBox().intersects(box) && (predicate == null || predicate.test(body)) ? List.of(body) : List.of();
                }
                @Override public <T extends net.minecraft.world.entity.Entity> List<T> getEntities(net.minecraft.world.level.entity.EntityTypeTest<net.minecraft.world.entity.Entity, T> filter,
                        AABB box, java.util.function.Predicate<? super T> predicate) { throw new AssertionError("Unused typed query"); }
                @Override public List<? extends net.minecraft.world.entity.player.Player> players() { return List.of(body); }
            };
            var query = new AABB(0, 0, 0, 1, 1, 1);
            for (double overlap : new double[]{-2e-7, -1e-7, -5e-8, 0, 5e-8, 1e-7, 2e-7}) {
                body.setBoundingBox(new AABB(1 - overlap, 0, 0, 2, 1, 1));
                assertEquals(overlap > 1e-7 ? 1 : 0, entities.getEntityCollisions(null, query).size());
            }
            body.setBoundingBox(query);
            assertTrue(entities.getEntityCollisions(null, new AABB(.5, 0, 0, .5, 1, 1)).isEmpty());
            assertTrue(entities.getEntityCollisions(body, query).isEmpty());
            return null;
        });
    }
    private static void compare(VoxelShape shape, RollbackVoxelIntersections.Grid grid, AABB box) {
        assertEquals(CollisionUtil.voxelShapeIntersectNoEmpty(shape, box), RollbackVoxelIntersections.intersects(grid, portable(box)), box::toString);
    }
    private static void compareEmpty(AABB box) { assertEquals(CollisionUtil.isEmpty(box), RollbackVoxelIntersections.empty(portable(box))); }
    private static Box portable(AABB box) { return new Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ); }
    private static RollbackVoxelIntersections.Grid grid(VoxelShape shape) {
        var nativeShape = (CollisionVoxelShape) shape; var cells = nativeShape.moonrise$getCachedVoxelData();
        return new RollbackVoxelIntersections.Grid() {
            @Override public int size(int axis) { return switch (axis) { case 0 -> cells.sizeX(); case 1 -> cells.sizeY(); case 2 -> cells.sizeZ(); default -> throw new AssertionError(); }; }
            @Override public double coordinate(int axis, int index) { return switch (axis) {
                case 0 -> nativeShape.moonrise$rootCoordinatesX()[index] + nativeShape.moonrise$offsetX();
                case 1 -> nativeShape.moonrise$rootCoordinatesY()[index] + nativeShape.moonrise$offsetY();
                case 2 -> nativeShape.moonrise$rootCoordinatesZ()[index] + nativeShape.moonrise$offsetZ();
                default -> throw new AssertionError();
            }; }
            @Override public boolean full(int x, int y, int z) { int index = z + y * cells.sizeZ() + x * cells.sizeY() * cells.sizeZ(); return (cells.voxelSet()[index >>> 6] & 1L << index) != 0; }
        };
    }
}

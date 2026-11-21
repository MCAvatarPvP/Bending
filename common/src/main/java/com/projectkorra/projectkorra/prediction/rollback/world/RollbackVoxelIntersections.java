package com.projectkorra.projectkorra.prediction.rollback.world;

import com.projectkorra.projectkorra.prediction.rollback.world.RollbackBlockStore.Box;

/** Paper/Moonrise 1.21.11 entity-query tolerances, independent of either loader's shape classes. */
public final class RollbackVoxelIntersections {
    public static final double EPSILON = 1.0E-7;
    private static final long MAXIMUM_CELLS = 1_048_576;
    /** Native voxel coordinates, including offsets, and occupied cells; axes are X=0, Y=1, Z=2. */
    public interface Grid {
        int size(int axis);
        double coordinate(int axis, int index);
        boolean full(int x, int y, int z);
    }
    private RollbackVoxelIntersections() { }

    public static boolean empty(Box box) {
        return box.maxX() - box.minX() < EPSILON || box.maxY() - box.minY() < EPSILON || box.maxZ() - box.minZ() < EPSILON;
    }
    public static boolean single(Grid grid) { return grid.size(0) == 1 && grid.size(1) == 1 && grid.size(2) == 1 && grid.full(0, 0, 0); }

    /** Mirrors CollisionUtil.voxelShapeIntersectNoEmpty, including its exact upper-bound convention. */
    public static boolean intersects(Grid grid, Box box) {
        int xSize = grid.size(0), ySize = grid.size(1), zSize = grid.size(2);
        if (xSize < 0 || ySize < 0 || zSize < 0 || xSize > MAXIMUM_CELLS || ySize > MAXIMUM_CELLS || zSize > MAXIMUM_CELLS
                || (long) xSize * ySize * zSize > MAXIMUM_CELLS) throw new IllegalStateException("Voxel intersection budget exceeded");
        if (xSize == 0 || ySize == 0 || zSize == 0) return false;
        int minX = Math.max(0, floor(grid, 0, box.minX() + EPSILON, 0, xSize));
        if (minX >= xSize) return false;
        int maxX = Math.min(xSize, floor(grid, 0, box.maxX() - EPSILON, minX, xSize) + 1);
        if (minX >= maxX) return false;
        int minY = Math.max(0, floor(grid, 1, box.minY() + EPSILON, 0, ySize));
        if (minY >= ySize) return false;
        int maxY = Math.min(ySize, floor(grid, 1, box.maxY() - EPSILON, minY, ySize) + 1);
        if (minY >= maxY) return false;
        int minZ = Math.max(0, floor(grid, 2, box.minZ() + EPSILON, 0, zSize));
        if (minZ >= zSize) return false;
        int maxZ = Math.min(zSize, floor(grid, 2, box.maxZ() - EPSILON, minZ, zSize) + 1);
        for (int x = minX; x < maxX; x++) for (int y = minY; y < maxY; y++) for (int z = minZ; z < maxZ; z++) {
            if (grid.full(x, y, z)) return true;
        }
        return false;
    }
    private static int floor(Grid grid, int axis, double value, int low, int high) {
        do {
            int middle = (low + high) >>> 1;
            if (value < grid.coordinate(axis, middle)) high = middle - 1;
            else low = middle + 1;
        } while (low <= high);
        return low - 1;
    }
}

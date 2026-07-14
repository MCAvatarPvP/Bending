package com.projectkorra.projectkorra.platform.mc.util;

import com.projectkorra.projectkorra.platform.mc.World;
import com.projectkorra.projectkorra.platform.mc.block.Block;

import java.util.Iterator;
import java.util.NoSuchElementException;

public class BlockIterator implements Iterator<Block> {
    private final World world;
    private final double startX;
    private final double startY;
    private final double startZ;
    private final double dirX;
    private final double dirY;
    private final double dirZ;
    private final double maxDistance;
    private final int stepX;
    private final int stepY;
    private final int stepZ;
    private final double tDeltaX;
    private final double tDeltaY;
    private final double tDeltaZ;
    private int blockX;
    private int blockY;
    private int blockZ;
    private int lastX;
    private int lastY;
    private int lastZ;
    private double tMaxX;
    private double tMaxY;
    private double tMaxZ;
    private boolean first = true;
    private boolean hasNext = true;

    public BlockIterator(World world, Vector start, Vector direction, double yOffset, int maxDistance) {
        if (world == null) throw new IllegalArgumentException("world cannot be null");
        if (start == null) throw new IllegalArgumentException("start cannot be null");
        if (direction == null || direction.lengthSquared() == 0)
            throw new IllegalArgumentException("direction cannot be zero");
        this.world = world;
        Vector origin = start.clone().add(new Vector(0, yOffset, 0));
        Vector normal = direction.clone().normalize();
        this.startX = origin.getX();
        this.startY = origin.getY();
        this.startZ = origin.getZ();
        this.dirX = normal.getX();
        this.dirY = normal.getY();
        this.dirZ = normal.getZ();
        this.maxDistance = Math.max(0, maxDistance);
        this.blockX = floor(this.startX);
        this.blockY = floor(this.startY);
        this.blockZ = floor(this.startZ);
        this.lastX = this.blockX;
        this.lastY = this.blockY;
        this.lastZ = this.blockZ;
        this.stepX = sign(this.dirX);
        this.stepY = sign(this.dirY);
        this.stepZ = sign(this.dirZ);
        this.tMaxX = initialT(this.startX, this.dirX, this.stepX, this.blockX);
        this.tMaxY = initialT(this.startY, this.dirY, this.stepY, this.blockY);
        this.tMaxZ = initialT(this.startZ, this.dirZ, this.stepZ, this.blockZ);
        this.tDeltaX = this.stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / this.dirX);
        this.tDeltaY = this.stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / this.dirY);
        this.tDeltaZ = this.stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / this.dirZ);
        this.hasNext = this.maxDistance > 0;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static int sign(double value) {
        return value > 0 ? 1 : value < 0 ? -1 : 0;
    }

    private static double initialT(double start, double direction, int step, int block) {
        if (step == 0) {
            return Double.POSITIVE_INFINITY;
        }
        double boundary = step > 0 ? block + 1.0 : block;
        return Math.max(0, (boundary - start) / direction);
    }

    @Override
    public boolean hasNext() {
        return hasNext;
    }

    @Override
    public Block next() {
        if (!hasNext()) throw new NoSuchElementException();
        if (first) {
            first = false;
        } else {
            step();
        }
        Block block = world.getBlockAt(blockX, blockY, blockZ);
        lastX = blockX;
        lastY = blockY;
        lastZ = blockZ;
        if (nextDistance() > maxDistance) {
            hasNext = false;
        }
        return block;
    }

    private void step() {
        if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
            blockX += stepX;
            tMaxX += tDeltaX;
        } else if (tMaxY <= tMaxZ) {
            blockY += stepY;
            tMaxY += tDeltaY;
        } else {
            blockZ += stepZ;
            tMaxZ += tDeltaZ;
        }
    }

    private double nextDistance() {
        return Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
    }
}

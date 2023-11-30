package com.projectkorra.projectkorra.util;


import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.EntityType;
import org.bukkit.util.Transformation;

import java.util.PriorityQueue;
import java.util.SplittableRandom;
import java.util.concurrent.ThreadLocalRandom;

/**
 * @author Sakrajin
 * Represents temporary display blocks (which are entiites) and handles reverting
 */
public class TempDisplayBlock {
    private static final PriorityQueue<TempDisplayBlock> REVERT_QUEUE = new PriorityQueue<>(100, (t1, t2) -> (int) (t1.revertTime - t2.revertTime));


    public static PriorityQueue<TempDisplayBlock> getRevertQueue()
    {
        return REVERT_QUEUE;
    }

    private BlockDisplay blockDisplay;

    private long revertTime;


    public TempDisplayBlock(Location loc, Material block, final long revertTime, double size, boolean glowing, Color color) {

        blockDisplay = (BlockDisplay) loc.getWorld().spawn(loc, EntityType.BLOCK_DISPLAY.getEntityClass(), (entity) ->
        {
            BlockDisplay bDisplay = (BlockDisplay)entity;
            SplittableRandom splittableRandom = new SplittableRandom();
            bDisplay.setBlock(block.createBlockData());
            Transformation transformation = bDisplay.getTransformation();
            //transformation.getTranslation().set(new Vector3d(-Math.cos(Math.toRadians(yaw))*size -size/2, -size/2,-Math.sin(Math.toRadians(yaw)*size) - size/2));
            transformation.getScale().set(size);
            bDisplay.setViewRange(30);
            //transformation.getLeftRotation().set(new AxisAngle4d(Math.toRadians(yaw), 0, 1, 0));
            bDisplay.setTransformation(transformation);
            if (glowing)
            {
                bDisplay.setGlowing(true);
                bDisplay.setGlowColorOverride(color);
            }

        });
        this.revertTime = System.currentTimeMillis() + revertTime;
        REVERT_QUEUE.add(this);
    }


    public void automaticRevert()
    {
        if (blockDisplay != null) {
            blockDisplay.remove();
        }
        REVERT_QUEUE.remove();
    }

    public void revert()
    {
        blockDisplay.remove();
    }


    public long getRevertTime() {
        return revertTime;
    }

    public void teleport(Location newLoc)
    {
        blockDisplay.teleport(newLoc);
    }
}


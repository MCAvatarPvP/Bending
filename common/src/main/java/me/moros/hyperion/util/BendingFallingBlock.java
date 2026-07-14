/*
 *   Copyright 2016, 2017, 2020 Moros <https://github.com/PrimordialMoros>
 *
 * 	  This file is part of Hyperion.
 *
 *    Hyperion is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    Hyperion is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with Hyperion.  If not, see <https://www.gnu.org/licenses/>.
 */

package me.moros.hyperion.util;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.object.EarthCosmetic;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class BendingFallingBlock {
    private static final Map<FallingBlock, BendingFallingBlock> instances = new ConcurrentHashMap<>();
    private static final Queue<BendingFallingBlock> bfbQueue = new PriorityQueue<>(Comparator.comparingLong(BendingFallingBlock::getExpirationTime));
    private final FallingBlock fallingBlock;
    private final CoreAbility ability;
    private final long expirationTime;

    public BendingFallingBlock(Location location, BlockData data, Vector velocity, CoreAbility abilityInstance, boolean gravity) {
        this(location, data, velocity, abilityInstance, gravity, 30000);
    }

    public BendingFallingBlock(Location location, BlockData data, Vector velocity, CoreAbility abilityInstance, boolean gravity, long delay) {
        BlockData blockData = data;
        EarthCosmetic cosmetic = abilityInstance.getBendingPlayer().getEarthCosmetic();
        if (EarthCosmetic.canReplace(cosmetic, data.getMaterial()) && abilityInstance.getElement() == Element.EARTH) {
            blockData = cosmetic.getMaterial().createBlockData();
        }
        fallingBlock = location.getWorld().spawnFallingBlock(location, blockData);
        fallingBlock.setVelocity(velocity);
        fallingBlock.setGravity(gravity);
        fallingBlock.setDropItem(false);

        expirationTime = System.currentTimeMillis() + delay;
        ability = abilityInstance;
        instances.put(fallingBlock, this);
        bfbQueue.add(this);
    }

    public static boolean isBendingFallingBlock(FallingBlock fb) {
        return instances.containsKey(fb);
    }

    public static BendingFallingBlock get(FallingBlock fb) {
        return instances.get(fb);
    }

    public static Set<BendingFallingBlock> getFromAbility(CoreAbility ability) {
        return instances.values().stream().filter(bfb -> bfb.getAbility().equals(ability)).collect(Collectors.toSet());
    }

    public static void manage() {
        final long currentTime = System.currentTimeMillis();
        while (!bfbQueue.isEmpty()) {
            final BendingFallingBlock bfb = bfbQueue.peek();
            if (currentTime > bfb.getExpirationTime()) {
                bfbQueue.poll();
                bfb.remove();
            } else {
                return;
            }
        }

        Iterator<BendingFallingBlock> iterator = instances.values().iterator();
        while (iterator.hasNext()) {
            BendingFallingBlock bfb = iterator.next();
            if (currentTime > bfb.getExpirationTime()) {
                bfb.getFallingBlock().remove();
                iterator.remove();
            }
        }
    }

    public static void removeAll() {
        bfbQueue.clear();
        instances.keySet().forEach(Entity::remove);
        instances.clear();
    }

    public CoreAbility getAbility() {
        return ability;
    }

    public FallingBlock getFallingBlock() {
        return fallingBlock;
    }

    public long getExpirationTime() {
        return expirationTime;
    }

    public void remove() {
        instances.remove(fallingBlock);
        fallingBlock.remove();
    }
}

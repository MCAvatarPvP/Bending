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

import com.projectkorra.projectkorra.prediction.rollback.RollbackTimeShift;

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.object.EarthCosmetic;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.FallingBlock;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.block.TempFallingBlockSync;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class BendingFallingBlock {
    private static final Map<FallingBlock, BendingFallingBlock> instances = new ConcurrentHashMap<>();
    public static final class ExpiryOrder implements Comparator<BendingFallingBlock> {
        @Override public int compare(BendingFallingBlock left, BendingFallingBlock right) {
            return Long.compare(left.getExpirationTime(), right.getExpirationTime());
        }
    }
    private static final Queue<BendingFallingBlock> bfbQueue = new PriorityQueue<>(new ExpiryOrder());
    private static Map<FallingBlock, BendingFallingBlock> members() {
        var duel = com.projectkorra.projectkorra.prediction.rollback.RollbackAddonActors.current();
        return duel == null ? instances : duel.falling;
    }
    private static Queue<BendingFallingBlock> expiry() {
        var duel = com.projectkorra.projectkorra.prediction.rollback.RollbackAddonActors.current();
        return duel == null ? bfbQueue : duel.fallingExpiry;
    }
    public static Set<BendingFallingBlock> managed() {
        var result = new HashSet<>(members().values());
        result.addAll(expiry());
        return result;
    }
    public static void resume(BendingFallingBlock actor) {
        if (com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active())
            throw new IllegalStateException("Restore confirmed actors on the live server");
        var existing = instances.putIfAbsent(actor.fallingBlock, actor);
        if (existing != null && existing != actor) throw new IllegalStateException("Temporary actor ownership changed");
        if (existing == null) bfbQueue.add(actor);
    }
    private final FallingBlock fallingBlock;
    private final CoreAbility ability;
    private long expirationTime;

    public BendingFallingBlock(Location location, BlockData data, Vector velocity, CoreAbility abilityInstance, boolean gravity) {
        this(location, data, velocity, abilityInstance, gravity, 30000);
    }

    public BendingFallingBlock(Location location, BlockData data, Vector velocity, CoreAbility abilityInstance, boolean gravity, long delay) {
        BlockData blockData = data;
        EarthCosmetic cosmetic = abilityInstance.getBendingPlayer().getEarthCosmetic();
        if (EarthCosmetic.canReplace(cosmetic, data.getMaterial()) && abilityInstance.getElement() == Element.EARTH) {
            blockData = cosmetic.getMaterial().createBlockData();
        }
        // Reserve the action-local identity before Paper emits the vanilla
        // spawn packet. The predicting client uses the matching identity to
        // hide Paper's copy and retain its continuously simulated projectile.
        final int predictionOrdinal = TempFallingBlockSync.prepare(abilityInstance, location, blockData);
        fallingBlock = location.getWorld().spawnFallingBlock(location, blockData);
        fallingBlock.setVelocity(velocity);
        fallingBlock.setGravity(gravity);
        fallingBlock.setDropItem(false);

        expirationTime = RollbackClock.millis() + delay;
        ability = abilityInstance;
        members().put(fallingBlock, this);
        expiry().add(this);
        TempFallingBlockSync.publish(abilityInstance, fallingBlock, predictionOrdinal);
    }

    public static boolean isBendingFallingBlock(FallingBlock fb) {
        return members().containsKey(fb);
    }

    public static BendingFallingBlock get(FallingBlock fb) {
        return members().get(fb);
    }

    public static Set<BendingFallingBlock> getFromAbility(CoreAbility ability) {
        return members().values().stream().filter(bfb -> bfb.getAbility().equals(ability)).collect(Collectors.toSet());
    }

    public static final class Manager implements Runnable {
        @Override public void run() { manage(); }
    }
    public static void manage() {
        final long currentTime = RollbackClock.millis();
        while (!expiry().isEmpty()) {
            final BendingFallingBlock bfb = expiry().peek();
            if (currentTime > bfb.getExpirationTime()) {
                expiry().poll();
                bfb.remove();
            } else {
                return;
            }
        }

        Iterator<BendingFallingBlock> iterator = members().values().iterator();
        while (iterator.hasNext()) {
            BendingFallingBlock bfb = iterator.next();
            if (currentTime > bfb.getExpirationTime()) {
                bfb.getFallingBlock().remove();
                iterator.remove();
            }
        }
    }

    public static void removeAll() {
        expiry().clear();
        members().keySet().forEach(Entity::remove);
        members().clear();
    }

    public CoreAbility getAbility() {
        return ability;
    }

    public FallingBlock getFallingBlock() {
        return fallingBlock;
    }

    public void rebase(RollbackTimeShift time) {
        time.once(this, () -> expirationTime = time.time(expirationTime));
    }

    public long getExpirationTime() {
        return expirationTime;
    }

    public void remove() {
        if (com.projectkorra.projectkorra.prediction.rollback.RollbackAddonActors.current() != null) expiry().remove(this);
        members().remove(fallingBlock, this);
        fallingBlock.remove();
    }
}

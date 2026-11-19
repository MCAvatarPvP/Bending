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

import com.projectkorra.projectkorra.prediction.rollback.RollbackClock;

import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.inventory.ItemStack;
import com.projectkorra.projectkorra.platform.mc.metadata.FixedMetadataValue;
import com.projectkorra.projectkorra.util.ParticleEffect;
import me.moros.hyperion.Hyperion;
import me.moros.hyperion.methods.CoreMethods;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class TempArmorStand {
    private static final Map<ArmorStand, TempArmorStand> instances = new ConcurrentHashMap<>();
    public static final class ExpiryOrder implements Comparator<TempArmorStand> {
        @Override public int compare(TempArmorStand left, TempArmorStand right) {
            return Long.compare(left.getExpirationTime(), right.getExpirationTime());
        }
    }
    private static final Queue<TempArmorStand> tasQueue = new PriorityQueue<>(new ExpiryOrder());
    private static Map<ArmorStand, TempArmorStand> members() {
        var duel = com.projectkorra.projectkorra.prediction.rollback.RollbackAddonActors.current();
        return duel == null ? instances : duel.stands;
    }
    private static Queue<TempArmorStand> expiry() {
        var duel = com.projectkorra.projectkorra.prediction.rollback.RollbackAddonActors.current();
        return duel == null ? tasQueue : duel.standExpiry;
    }
    public static Set<TempArmorStand> managed() {
        var result = new HashSet<>(members().values());
        result.addAll(expiry());
        return result;
    }
    public static void resume(TempArmorStand actor) {
        if (com.projectkorra.projectkorra.prediction.rollback.RollbackDomain.active())
            throw new IllegalStateException("Restore confirmed actors on the live server");
        var existing = instances.putIfAbsent(actor.armorStand, actor);
        if (existing != null && existing != actor) throw new IllegalStateException("Temporary actor ownership changed");
        if (existing == null) tasQueue.add(actor);
    }
    private final ArmorStand armorStand;
    private final CoreAbility ability;
    private final Material headMaterial;
    private final long expirationTime;
    private final boolean particles;

    public TempArmorStand(CoreAbility abilityInstance, Location location, Material material, long delay) {
        this(abilityInstance, location, material, delay, false);
    }

    public TempArmorStand(CoreAbility abilityInstance, Location location, Material material, long delay, boolean showRemoveParticles) {
        headMaterial = material;
        armorStand = location.getWorld().spawn(location, ArmorStand.class, entity -> {
            entity.setInvulnerable(true);
            entity.setVisible(false);
            entity.setGravity(false);
            entity.getEquipment().setHelmet(new ItemStack(headMaterial));
            entity.setMetadata(CoreMethods.NO_INTERACTION_KEY, new FixedMetadataValue(Hyperion.getPlugin(), ""));
        });
        expirationTime = RollbackClock.millis() + delay;
        ability = abilityInstance;
        members().put(armorStand, this);
        expiry().add(this);
        particles = showRemoveParticles;
        showParticles(true);
    }

    public static boolean isTempArmorStand(ArmorStand as) {
        return members().containsKey(as);
    }

    public static TempArmorStand get(ArmorStand as) {
        return members().get(as);
    }

    public static Set<TempArmorStand> getFromAbility(CoreAbility ability) {
        return members().values().stream().filter(tas -> tas.getAbility().equals(ability)).collect(Collectors.toSet());
    }

    public static final class Manager implements Runnable {
        @Override public void run() { manage(); }
    }
    public static void manage() {
        final long currentTime = RollbackClock.millis();
        while (!expiry().isEmpty()) {
            final TempArmorStand tas = expiry().peek();
            if (currentTime > tas.getExpirationTime()) {
                expiry().poll();
                tas.remove();
            } else {
                return;
            }
        }
    }

    public static void removeAll() {
        expiry().clear();
        members().keySet().forEach(Entity::remove);
        members().clear();
    }

    public void showParticles(boolean show) {
        if (show) {
            ParticleEffect.BLOCK_CRACK.display(armorStand.getEyeLocation().add(0, 0.2, 0), 4, 0.25, 0.125, 0.25, 0, headMaterial.createBlockData());
            ParticleEffect.BLOCK_DUST.display(armorStand.getEyeLocation().add(0, 0.2, 0), 6, 0.25, 0.125, 0.25, 0, headMaterial.createBlockData());
        }
    }

    public CoreAbility getAbility() {
        return ability;
    }

    public ArmorStand getArmorStand() {
        return armorStand;
    }

    public long getExpirationTime() {
        return expirationTime;
    }

    public void remove() {
        if (com.projectkorra.projectkorra.prediction.rollback.RollbackAddonActors.current() != null) expiry().remove(this);
        members().remove(armorStand, this);
        showParticles(particles);
        armorStand.remove();
    }
}

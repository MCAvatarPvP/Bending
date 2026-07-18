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

package me.moros.hyperion.abilities.waterbending;

import com.projectkorra.projectkorra.Element;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.CoreAbility;
import com.projectkorra.projectkorra.ability.IceAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.airbending.AirShield;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Color;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.*;
import com.projectkorra.projectkorra.waterbending.ice.PhaseChange;
import me.moros.hyperion.Hyperion;
import me.moros.hyperion.configuration.ConfigManager;
import me.moros.hyperion.methods.CoreMethods;
import me.moros.hyperion.util.BendingFallingBlock;
import me.moros.hyperion.util.MaterialCheck;

import java.util.*;

public class IceBreath extends IceAbility implements AddonAbility {
    private final Set<Location> line = new LinkedHashSet<>();
    private Location location;

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.RANGE)
    private int range;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.CHARGE_DURATION)
    private long chargeTime;
    @Attribute(Attribute.DURATION)
    private long frostDuration;

    private double currentRange;
    private boolean charged;
    private boolean released;
    private final Random gameplayRandom;

    public IceBreath(Player player) {
        super(player);
        this.gameplayRandom = PredictionDeterminism.random(player == null ? null : player.getUniqueId(),
                getClass().getName() + ":ice-lifetime");

        if (!bPlayer.canBend(this)) {
            return;
        }

        damage = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Water.IceBreath.Damage");
        range = ConfigManager.getConfig(bPlayer).getInt("Abilities.Water.IceBreath.Range");
        cooldown = ConfigManager.getConfig(bPlayer).getLong("Abilities.Water.IceBreath.Cooldown");
        chargeTime = ConfigManager.getConfig(bPlayer).getLong("Abilities.Water.IceBreath.ChargeTime");
        frostDuration = ConfigManager.getConfig(bPlayer).getLong("Abilities.Water.IceBreath.FrostDuration");

        charged = chargeTime <= 0;
        released = false;
        currentRange = 0;
        location = player.getEyeLocation();

        start();
    }

    @Override
    public void progress() {
        if (!bPlayer.canBendIgnoreCooldowns(this)) {
            remove();
            return;
        }

        if (charged) {
            if (released) {
                Iterator<Location> it = line.iterator();
                for (int i = 0; i < 4; i++) {
                    if (it.hasNext()) {
                        location = it.next();
                        currentRange += 0.25;
                        visualizeBreath(currentRange * 0.4, currentRange * 0.025);
                        it.remove();
                    } else {
                        remove();
                        return;
                    }
                }
                checkArea(1.5 + currentRange * 0.2);
            } else {
                if (player.isSneaking() && chargeTime != 0) {
                    CoreMethods.playFocusParticles(player);
                } else {
                    if (calculateBreath()) {
                        bPlayer.addCooldown(this);
                        released = true;
                        freezeArea();
                    } else {
                        remove();
                    }
                }
            }
        } else {
            if (!player.isSneaking()) {
                remove();
                return;
            }
            if (System.currentTimeMillis() > getStartTime() + chargeTime) {
                charged = true;
            }
        }
    }

    private void visualizeBreath(double offset, double particleSize) {
        ParticleEffect.SNOW_SHOVEL.display(location, 5, offset, 1, offset, particleSize);
        ParticleEffect.BLOCK_CRACK.display(location, 4, offset, 1, offset, particleSize, Material.ICE.createBlockData());
        ParticleEffect.SPELL_MOB.display(CoreMethods.withGaussianOffset(location, offset), 0, 220, 220, 220, 0.003, new Particle.DustOptions(Color.fromRGB(220, 220, 220), 1));
        ParticleEffect.SPELL_MOB.display(CoreMethods.withGaussianOffset(location, offset), 0, 180, 180, 255, 0.0035, new Particle.DustOptions(Color.fromRGB(180, 180, 255), 1));
    }

    private boolean calculateBreath() {
        range = (int) getNightFactor(range, player.getWorld());
        final Vector direction = player.getEyeLocation().getDirection();
        final Location origin = player.getEyeLocation().clone();
        for (Location loc : CoreMethods.getLinePoints(origin, origin.clone().add(direction.clone().multiply(range)), 4 * range)) {
            if (!line.contains(loc) && !isTransparent(loc.getBlock())) {
                break;
            }
            line.add(loc);
        }
        location = origin.clone();
        return !line.isEmpty();
    }

    private void freezeArea() {
        final Location center = GeneralMethods.getTargetedLocation(player, range);

        final List<BlockFace> faces = new ArrayList<>();
        final Vector toPlayer = GeneralMethods.getDirection(center, player.getEyeLocation());
        final double[] vars = {toPlayer.getX(), toPlayer.getY(), toPlayer.getZ()};
        for (int i = 0; i < 3; i++) {
            if (vars[i] != 0) {
                faces.add(GeneralMethods.getBlockFaceFromValue(i, vars[i]));
            }
        }
        int radius = (int) getNightFactor(2, player.getWorld());
        for (final Location l : GeneralMethods.getCircle(center, radius, 1, false, true, 0)) {
            final Block b = l.getBlock();
            for (final BlockFace face : faces) {
                if (b.getRelative(face).getType().isAir()) {
                    if (!isWater(b) || RegionProtection.isRegionProtected(this.player, b.getLocation())) {
                        continue;
                    }
                    new TempBlock(b, Material.ICE.createBlockData(), frostDuration);
                    break;
                }
            }
        }
    }

    private void checkArea(double radius) {
        for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location, radius)) {
            if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId() && !(entity instanceof ArmorStand)) {
                if (entity instanceof Player && Commands.invincible.contains(entity.getName())) {
                    continue;
                }
                DamageHandler.damageEntity(entity, getNightFactor(damage, player.getWorld()), this);
                if (entity.isValid()) {
                    final MovementHandler mh = new MovementHandler((LivingEntity) entity, CoreAbility.getAbility(IceCrawl.class));
                    mh.stopWithDuration(frostDuration, Element.ICE.getColor() + "* Frozen *");
                    new BendingFallingBlock(entity.getLocation().clone().add(0, -0.2, 0), Material.PACKED_ICE.createBlockData(), new Vector(), this, false, frostDuration);
                    new TempPotionEffect((LivingEntity) entity, new PotionEffect(PotionEffectType.SLOWNESS, (int) (frostDuration / 50), 3));
                }
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig(bPlayer).getBoolean("Abilities.Water.IceBreath.Enabled");
    }

    @Override
    public String getName() {
        return "IceBreath";
    }

    @Override
    public String getDescription() {
        return ConfigManager.getConfig(bPlayer).getString("Abilities.Water.IceBreath.Description");
    }

    @Override
    public String getAuthor() {
        return Hyperion.getAuthor();
    }

    @Override
    public String getVersion() {
        return Hyperion.getVersion();
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public List<Location> getLocations() {
        return new ArrayList<>(line);
    }

    @Override
    public boolean isCollidable() {
        return released;
    }

    @Override
    public double getCollisionRadius() {
        return 0.5;
    }

    @Override
    public void handleCollision(Collision collision) {
        if (collision.getAbilitySecond() instanceof AirShield) {
            int radius = (int) collision.getAbilitySecond().getCollisionRadius();
            for (Location testLoc : GeneralMethods.getCircle(collision.getLocationSecond(), radius, radius, true, true, 0)) {
                final Block testBlock = testLoc.getBlock();
                if (MaterialCheck.isLeaf(testBlock)) testBlock.breakNaturally();
                if (testBlock.getType().isAir() || isWater(testBlock)) {
                    PhaseChange.getFrozenBlocksMap().put(new TempBlock(testBlock, Material.ICE.createBlockData(),
                            this.gameplayRandom.nextInt(1000) + frostDuration), player);
                }
            }
        }
        super.handleCollision(collision);
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }
}

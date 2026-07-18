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
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.ArmorStand;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import com.projectkorra.projectkorra.platform.mc.util.NumberConversions;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.prediction.PredictionDeterminism;
import com.projectkorra.projectkorra.region.RegionProtection;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.MovementHandler;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.util.TempPotionEffect;
import com.projectkorra.projectkorra.waterbending.ice.PhaseChange;
import me.moros.hyperion.Hyperion;
import me.moros.hyperion.configuration.ConfigManager;
import me.moros.hyperion.methods.CoreMethods;
import me.moros.hyperion.util.BendingFallingBlock;
import me.moros.hyperion.util.TempArmorStand;

import java.util.Collections;
import java.util.Random;

public class IceCrawl extends IceAbility implements AddonAbility {
    private Location location;
    private Location endLocation;
    private LivingEntity target;
    private Vector direction;
    private Block sourceBlock;

    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private int range;
    @Attribute(Attribute.SELECT_RANGE)
    private int selectRange;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute("RegenDelay")
    private long iceDuration;

    private boolean launched;
    private boolean locked;
    private final Random visualRandom;

    public IceCrawl(Player player) {
        super(player);
        this.visualRandom = PredictionDeterminism.random(player == null ? null : player.getUniqueId(),
                getClass().getName() + ":visuals");

        if (!bPlayer.canBend(this)) {
            return;
        }

        if (hasAbility(player, IceCrawl.class)) {
            getAbility(player, IceCrawl.class).prepare();
            return;
        }

        damage = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Water.IceCrawl.Damage");
        cooldown = ConfigManager.getConfig(bPlayer).getLong("Abilities.Water.IceCrawl.Cooldown");
        range = ConfigManager.getConfig(bPlayer).getInt("Abilities.Water.IceCrawl.Range");
        selectRange = ConfigManager.getConfig(bPlayer).getInt("Abilities.Water.IceCrawl.SelectRange");
        iceDuration = ConfigManager.getConfig(bPlayer).getLong("Abilities.Water.IceCrawl.IceDuration");
        duration = ConfigManager.getConfig(bPlayer).getLong("Abilities.Water.IceCrawl.FreezeDuration");

        range = (int) getNightFactor(range, player.getWorld());
        selectRange = (int) getNightFactor(selectRange, player.getWorld());
        duration = (long) getNightFactor(duration, player.getWorld());

        launched = false;

        if (prepare()) {
            start();
        }
    }

    public static void shootLine(Player player) {
        if (hasAbility(player, IceCrawl.class)) {
            getAbility(player, IceCrawl.class).shootLine();
        }
    }

    @Override
    public void progress() {
        if (launched) {
            if (!bPlayer.canBendIgnoreBindsCooldowns(this)) {
                remove();
                return;
            }
            if (locked) {
                if (target == null || !target.isValid() || (target instanceof Player && !((Player) target).isOnline()) || !target.getWorld().equals(location.getWorld())) {
                    locked = false;
                } else {
                    if (target.getLocation().distanceSquared(endLocation) < 25) {
                        endLocation = target.getLocation().clone();
                        direction = CoreMethods.calculateFlatVector(sourceBlock.getLocation(), endLocation);
                        if (direction.lengthSquared() == 0) {
                            locked = false;
                        }
                    } else {
                        locked = false;
                    }
                }
            }
            advanceLocation();
            checkDamage();
            if (this.visualRandom.nextInt(5) == 0) playIcebendingSound(location);
        } else {
            if (!bPlayer.canBendIgnoreCooldowns(this) || sourceBlock.getLocation().distanceSquared(player.getLocation()) > Math.pow(selectRange + 5, 2)) {
                remove();
                return;
            }
            if (isWater(sourceBlock) || isIce(sourceBlock)) {
                playFocusWaterEffect(sourceBlock);
            } else {
                remove();
            }
        }
    }

    private void advanceLocation() {
        if (isLava(location.getBlock())) {
            CoreMethods.playExtinguishEffect(location.clone().add(0, 0.2, 0), 8);
            remove();
        }

        if (isWater(location.getBlock().getRelative(BlockFace.DOWN)))
            PhaseChange.getFrozenBlocksMap().put(new TempBlock(location.getBlock().getRelative(BlockFace.DOWN), Material.ICE.createBlockData(), iceDuration), player);
        double x = this.visualRandom.nextDouble(-0.125, 0.125);
        double z = this.visualRandom.nextDouble(-0.125, 0.125);
        new TempArmorStand(this, location.clone().add(x, -2, z), Material.PACKED_ICE, 1400);

        location.add(direction.clone().multiply(0.7));
        final Block baseBlock = location.getBlock().getRelative(BlockFace.DOWN);
        if (!isValidBlock(baseBlock)) {
            if (isValidBlock(baseBlock.getRelative(BlockFace.UP))) {
                location.add(0, 1, 0);
            } else if (isValidBlock(baseBlock.getRelative(BlockFace.DOWN))) {
                location.add(0, -1, 0);
            } else {
                remove();
                return;
            }
        } else if (endLocation.getBlockY() != location.getBlockY()) { // Advance location vertically while under water
            if (endLocation.getBlockY() > location.getBlockY() && isValidBlock(baseBlock.getRelative(BlockFace.UP))) {
                location.add(0, 1, 0);
            } else if (endLocation.getBlockY() < location.getBlockY() && isValidBlock(baseBlock.getRelative(BlockFace.DOWN))) {
                location.add(0, -1, 0);
            }
        }
        if (RegionProtection.isRegionProtected(this, location) || location.distanceSquared(sourceBlock.getLocation()) > range * range) {
            remove();
        }
    }

    private boolean isValidBlock(final Block block) {
        if (!isTransparent(block.getRelative(BlockFace.UP))) return false;
        return isWater(block) || isIce(block) || GeneralMethods.isSolid(block);
    }

    private void checkDamage() {
        boolean hasHit = false;
        for (Entity entity : GeneralMethods.getEntitiesAroundPoint(location, 0.8)) {
            if (entity instanceof LivingEntity && entity.getEntityId() != player.getEntityId() && !(entity instanceof ArmorStand)) {
                if (entity instanceof Player && Commands.invincible.contains(entity.getName())) {
                    continue;
                }
                DamageHandler.damageEntity(entity, getNightFactor(damage, player.getWorld()), this);
                if (entity.isValid()) {
                    final MovementHandler mh = new MovementHandler((LivingEntity) entity, CoreAbility.getAbility(IceCrawl.class));
                    mh.stopWithDuration(duration, Element.ICE.getColor() + "* Frozen *");
                    new BendingFallingBlock(entity.getLocation().clone().add(0, -0.2, 0), Material.PACKED_ICE.createBlockData(), new Vector(), this, false, duration);
                    new TempPotionEffect((LivingEntity) entity, new PotionEffect(PotionEffectType.SLOWNESS, NumberConversions.round(duration / 50F), 5));
                }
                hasHit = true;
            }
        }
        if (hasHit) {
            remove();
        }
    }

    public boolean prepare() {
        if (launched) return false;
        Block block = getIceSourceBlock(player, selectRange);
        if (block == null) {
            block = getWaterSourceBlock(player, selectRange, false);
        }

        if (block == null || (!isWater(block) && !isIce(block)) || !isTransparent(block.getRelative(BlockFace.UP))) {
            if (isStarted()) remove();
            return false;
        }

        sourceBlock = block;
        playFocusWaterEffect(sourceBlock);
        location = sourceBlock.getLocation();
        return true;
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig(bPlayer).getBoolean("Abilities.Water.IceCrawl.Enabled");
    }

    @Override
    public String getName() {
        return "IceCrawl";
    }

    @Override
    public String getDescription() {
        return ConfigManager.getConfig(bPlayer).getString("Abilities.Water.IceCrawl.Description");
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
    public boolean isCollidable() {
        return launched;
    }

    @Override
    public double getCollisionRadius() {
        return 0.8;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    private void shootLine() {
        if (launched) return;
        final Entity targetedEntity = GeneralMethods.getTargetedEntity(player, range + selectRange, Collections.singletonList(player));
        if (targetedEntity instanceof LivingEntity && targetedEntity.getLocation().distanceSquared(location) <= range * range) {
            locked = true;
            target = (LivingEntity) targetedEntity;
            endLocation = target.getLocation().clone();
        } else {
            endLocation = GeneralMethods.getTargetedLocation(player, range, Material.WATER);
        }
        location = sourceBlock.getLocation().clone().add(0.5, 1.25, 0.5);
        direction = CoreMethods.calculateFlatVector(location, endLocation);
        if (direction.lengthSquared() == 0) {
            direction = player.getLocation().getDirection().setY(0).normalize();
        }
        if (direction.lengthSquared() == 0) {
            remove();
            return;
        }
        launched = true;
        playIcebendingSound(sourceBlock.getLocation());
        bPlayer.addCooldown(this);
    }
}

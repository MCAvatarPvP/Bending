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

package me.moros.hyperion.abilities.earthbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Material;
import com.projectkorra.projectkorra.platform.mc.entity.*;
import com.projectkorra.projectkorra.platform.mc.util.Transformation;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.DamageHandler;
import com.projectkorra.projectkorra.util.ParticleEffect;
import me.moros.hyperion.Hyperion;
import me.moros.hyperion.configuration.ConfigManager;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class EarthGlove extends EarthAbility implements AddonAbility {
    private static final Map<UUID, Side> lastUsedSide = new ConcurrentHashMap<>();
    private static final Map<UUID, EarthGlove> ACTIVE_GLOVE_PARTS = new ConcurrentHashMap<>();
    private static final List<GlovePart> GLOVE_MODEL = List.of(
            new GlovePart("palm", 0.0F, 0.0F, 0.0F,
                    0.52F, 0.46F, 0.34F, 0.0F),
            new GlovePart("cuff", 0.0F, -0.03F, -0.30F,
                    0.38F, 0.34F, 0.26F, 0.0F),
            new GlovePart("index", -0.225F, 0.17F, 0.22F,
                    0.14F, 0.20F, 0.24F, 0.0F),
            new GlovePart("middle", -0.075F, 0.19F, 0.23F,
                    0.14F, 0.22F, 0.25F, 0.0F),
            new GlovePart("ring", 0.075F, 0.18F, 0.225F,
                    0.14F, 0.21F, 0.245F, 0.0F),
            new GlovePart("little", 0.225F, 0.15F, 0.21F,
                    0.14F, 0.18F, 0.23F, 0.0F),
            new GlovePart("thumb", -0.32F, -0.04F, 0.09F,
                    0.18F, 0.29F, 0.21F, (float) Math.toRadians(-28.0D))
    );
    private static final double COLLISION_SAMPLE_DISTANCE = 0.2D;
    public boolean returning;
    public boolean grabbed;
    private LivingEntity grabbedTarget;
    private Vector lastVelocity;
    private BlockDisplay glove;
    private Location gloveLocation;
    private final List<BlockDisplay> gloveParts = new ArrayList<>(GLOVE_MODEL.size());
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.RANGE)
    private int range;
    @Attribute(Attribute.SPEED)
    private int speed;
    @Attribute("Grab" + Attribute.SPEED)
    private int grabSpeed;
    public EarthGlove(Player player) {
        super(player);

        if (getAbilities(player, EarthGlove.class).size() >= 2 || !bPlayer.canBendIgnoreCooldowns(this)) {
            return;
        }

        damage = ConfigManager.getConfig(bPlayer).getDouble("Abilities.Earth.EarthGlove.Damage");
        cooldown = ConfigManager.getConfig(bPlayer).getLong("Abilities.Earth.EarthGlove.Cooldown");
        range = ConfigManager.getConfig(bPlayer).getInt("Abilities.Earth.EarthGlove.Range");
        speed = ConfigManager.getConfig(bPlayer).getInt("Abilities.Earth.EarthGlove.Speed");
        grabSpeed = ConfigManager.getConfig(bPlayer).getInt("Abilities.Earth.EarthGlove.GrabSpeed");

        if (launchEarthGlove()) {
            start();
        }
    }

    public static void attemptDestroy(final Player player) {
        for (Entity targetedEntity : GeneralMethods.getEntitiesAroundPoint(player.getEyeLocation(), 8)) {
            if (targetedEntity instanceof BlockDisplay && player.hasLineOfSight(targetedEntity)) {
                final EarthGlove ability = ACTIVE_GLOVE_PARTS.get(targetedEntity.getUniqueId());
                if (ability != null && !player.equals(ability.getPlayer())) {
                    ability.shatterGlove();
                    return;
                }
            }
        }
    }

    public static String getCooldownForSide(Side s) {
        return switch (s) {
            case LEFT -> "EarthGloveLeft";
            case RIGHT -> "EarthGloveRight";
        };
    }

    @Override
    public void progress() {
        if (!bPlayer.canBendIgnoreBindsCooldowns(this) || glove == null || !glove.isValid()) {
            remove();
            return;
        }
        if (this.gloveLocation == null || this.gloveLocation.getWorld() != player.getWorld()
                || this.gloveLocation.distanceSquared(player.getLocation()) > Math.pow(range + 5, 2)) {
            remove();
            return;
        }
        if (this.gloveLocation.distanceSquared(player.getLocation()) > range * range) {
            returning = true;
        }

        if (!this.grabbed && this.advanceGlove()) {
            shatterGlove();
            return;
        }
        if (returning) {
            if (!player.isSneaking()) {
                shatterGlove();
                return;
            }
            Location returnLocation = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(1.5));
            if (this.gloveLocation.distanceSquared(returnLocation) < 1) {
                if (grabbed && grabbedTarget != null) grabbedTarget.setVelocity(new Vector());
                remove();
                return;
            }
            if (grabbed) {
                if (grabbedTarget == null || !grabbedTarget.isValid() || (grabbedTarget instanceof Player && !((Player) grabbedTarget).isOnline())) {
                    shatterGlove();
                    return;
                }
                grabbedTarget.setVelocity(GeneralMethods.getDirection(grabbedTarget.getLocation(), returnLocation).normalize().multiply(grabSpeed));
                teleportGlove(grabbedTarget.getEyeLocation().subtract(0, grabbedTarget.getHeight() / 2, 0));
                return;
            } else {
                setGloveVelocity(GeneralMethods.getDirection(this.gloveLocation, returnLocation).normalize().multiply(speed));
            }
        } else {
            setGloveVelocity(lastVelocity.clone().normalize().multiply(speed));
            checkDamage();
            if (grabbed) {
                Location returnLocation = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(1.5));
                final Vector returnVector = GeneralMethods.getDirection(this.gloveLocation, returnLocation).normalize();
                grabbedTarget.setVelocity(returnVector.clone().multiply(grabSpeed));
                setGloveVelocity(returnVector.clone().multiply(grabSpeed));
                return;
            }
        }
    }

    private boolean launchEarthGlove() {
        final Location gloveSpawnLocation;
        Side side = lastUsedSide.get(player.getUniqueId());
        if (side != null && bPlayer.isOnCooldown(getCooldownForSide(side))) {
            return false;
        }
        side = lastUsedSide.compute(player.getUniqueId(), (u, s) -> (s == null || s == Side.LEFT) ? Side.RIGHT : Side.LEFT);
        bPlayer.addCooldown(getCooldownForSide(side), cooldown);
        if (side == Side.RIGHT) {
            gloveSpawnLocation = GeneralMethods.getRightSide(player.getLocation(), 0.5).add(0, 0.8, 0);
        } else {
            gloveSpawnLocation = GeneralMethods.getLeftSide(player.getLocation(), 0.5).add(0, 0.8, 0);
        }
        final Entity targetedEntity = GeneralMethods.getTargetedEntity(player, range, Collections.singletonList(player));
        final Vector velocityVector;
        if (targetedEntity instanceof LivingEntity) {
            Location targetLoc = ((LivingEntity) targetedEntity).getEyeLocation().subtract(0, targetedEntity.getHeight() / 2, 0);
            velocityVector = GeneralMethods.getDirection(gloveSpawnLocation, targetLoc);
        } else {
            velocityVector = GeneralMethods.getDirection(gloveSpawnLocation, GeneralMethods.getTargetedLocation(player, range));
        }
        glove = buildGlove(gloveSpawnLocation);
        setGloveVelocity(velocityVector.normalize().multiply(speed));
        return true;
    }

    private BlockDisplay buildGlove(final Location spawnLocation) {
        this.gloveLocation = spawnLocation.clone();
        final var stone = Material.STONE.createBlockData();
        for (final GlovePart ignored : GLOVE_MODEL) {
            final BlockDisplay display = spawnLocation.getWorld().spawn(spawnLocation, BlockDisplay.class);
            display.setBlock(stone);
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setGravity(false);
            display.setSilent(true);
            display.setBillboard(Display.Billboard.FIXED);
            display.setShadowRadius(0.16F);
            display.setShadowStrength(0.75F);
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(1);
            display.setTeleportDuration(1);
            display.setViewRange(32.0F);
            this.gloveParts.add(display);
            ACTIVE_GLOVE_PARTS.put(display.getUniqueId(), this);
        }
        final BlockDisplay anchor = this.gloveParts.get(0);
        this.updateGloveModel();
        return anchor;
    }

    private boolean advanceGlove() {
        if (this.gloveLocation == null || this.lastVelocity == null
                || this.lastVelocity.lengthSquared() == 0.0D) return false;
        final int samples = Math.max(1, (int) Math.ceil(
                this.lastVelocity.length() / COLLISION_SAMPLE_DISTANCE));
        for (int sample = 1; sample <= samples; sample++) {
            final Location point = this.gloveLocation.clone().add(
                    this.lastVelocity.clone().multiply((double) sample / samples));
            if (!point.getBlock().isPassable()) return true;
        }
        this.gloveLocation.add(this.lastVelocity);
        this.updateGloveModel();
        return false;
    }

    private void teleportGlove(final Location location) {
        if (location == null || location.getWorld() == null) return;
        this.gloveLocation = location.clone();
        this.updateGloveModel();
    }

    private void updateGloveModel() {
        if (this.gloveLocation == null || this.gloveParts.size() != GLOVE_MODEL.size()) return;
        Vector facing = this.lastVelocity == null ? new Vector() : this.lastVelocity.clone();
        if (facing.lengthSquared() < 1.0E-6D) facing = this.player.getEyeLocation().getDirection();
        if (facing.lengthSquared() < 1.0E-6D) facing = new Vector(0.0D, 0.0D, 1.0D);
        facing.normalize();
        final float yaw = (float) Math.atan2(facing.getX(), facing.getZ());
        final float pitch = (float) -Math.asin(Math.max(-1.0D, Math.min(1.0D, facing.getY())));
        final Quaternionf modelRotation = new Quaternionf().rotateY(yaw).rotateX(pitch);

        for (int index = 0; index < GLOVE_MODEL.size(); index++) {
            final BlockDisplay display = this.gloveParts.get(index);
            if (display == null || !display.isValid()) continue;
            final GlovePart part = GLOVE_MODEL.get(index);
            final Vector3f offset = new Vector3f(part.offsetX(), part.offsetY(), part.offsetZ());
            modelRotation.transform(offset);
            final Location partLocation = this.gloveLocation.clone().add(offset.x, offset.y, offset.z);
            partLocation.setYaw(0.0F);
            partLocation.setPitch(0.0F);
            display.setTransformation(glovePartTransformation(part, modelRotation));
            display.teleport(partLocation);
        }
    }

    private static Transformation glovePartTransformation(final GlovePart part,
                                                           final Quaternionf modelRotation) {
        final Quaternionf rotation = new Quaternionf(modelRotation).rotateZ(part.roll());
        final Vector3f translation = new Vector3f(
                part.width() * 0.5F, part.height() * 0.5F, part.depth() * 0.5F);
        rotation.transform(translation);
        translation.negate();
        return new Transformation(translation, rotation,
                new Vector3f(part.width(), part.height(), part.depth()), new Quaternionf());
    }

    public void grabTarget(final LivingEntity entity) {
        if (grabbed || grabbedTarget != null || entity == null) {
            return;
        }
        returning = true;
        grabbed = true;
        grabbedTarget = entity;
        teleportGlove(grabbedTarget.getEyeLocation().subtract(0, grabbedTarget.getHeight() / 2, 0));
    }

    public void checkDamage() {
        Location testLocation = glove.getLocation().clone();
        for (Entity entity : GeneralMethods.getEntitiesAroundPoint(testLocation, 0.8)) {
            if (entity instanceof LivingEntity livingEntity && entity.getEntityId() != player.getEntityId() && !(entity instanceof ArmorStand)) {
                if (entity instanceof Player && Commands.invincible.contains(entity.getName())) {
                    continue;
                }
                if (player.isSneaking()) {
                    grabTarget(livingEntity);
                    return;
                }
                DamageHandler.damageEntity(livingEntity, damage, this);
                livingEntity.setNoDamageTicks(0);
                GeneralMethods.setVelocity(this, livingEntity, new Vector());
                shatterGlove();
                return;
            }
        }
    }

    public void setGloveVelocity(final Vector velocity) {
        if (velocity == null) return;
        lastVelocity = velocity.clone();
        this.updateGloveModel();
    }

    @Override
    public boolean isEnabled() {
        return ConfigManager.getConfig(bPlayer).getBoolean("Abilities.Earth.EarthGlove.Enabled");
    }

    @Override
    public String getName() {
        return "EarthGlove";
    }

    @Override
    public String getDescription() {
        return ConfigManager.getConfig(bPlayer).getString("Abilities.Earth.EarthGlove.Description");
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
        return this.gloveLocation == null ? player.getLocation() : this.gloveLocation.clone();
    }

    @Override
    public boolean isCollidable() {
        return true;
    }

    @Override
    public double getCollisionRadius() {
        return 0.6;
    }

    @Override
    public void handleCollision(Collision collision) {
        collision.setRemovingSecond(collision.getAbilitySecond() instanceof EarthGlove);
        super.handleCollision(collision);
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void remove() {
        for (final BlockDisplay part : List.copyOf(this.gloveParts)) {
            if (part == null) continue;
            ACTIVE_GLOVE_PARTS.remove(part.getUniqueId(), this);
            if (part.isValid()) part.remove();
        }
        this.gloveParts.clear();
        this.glove = null;
        super.remove();
    }

    public void shatterGlove() {
        if (this.glove == null || !this.glove.isValid()) {
            remove();
            return;
        }
        final Location location = this.getLocation();
        ParticleEffect.BLOCK_CRACK.display(location, 7, 0.18, 0.18, 0.18, Material.STONE.createBlockData());
        ParticleEffect.BLOCK_DUST.display(location, 5, 0.12, 0.12, 0.12, Material.STONE.createBlockData());
        remove();
    }

    private record GlovePart(String name, float offsetX, float offsetY, float offsetZ,
                             float width, float height, float depth, float roll) {
    }

    public enum Side {RIGHT, LEFT}
}

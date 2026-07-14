package com.projectkorra.projectkorra.earthbending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.EarthAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.command.Commands;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.block.data.BlockData;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.ParticleEffect;

import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Catapult extends EarthAbility {

    private static final Set<UUID> AWAITING_LANDING = ConcurrentHashMap.newKeySet();

    private double stageTimeMult;
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    private long clickCd;
    private Location origin;
    private Location target;

    private int stage;
    private long stageStart;
    private boolean charging;
    private boolean activationHandled;
    private Vector up;
    private double angle;
    private boolean cancelWithAngle;
    private BlockData bentBlockData;
    private boolean removeFireTick;
    private double launchPower;
    private boolean launchOthers;
    private boolean playRegularSound;
    private boolean sneaked;

    public Catapult(final Player player, final boolean sneak) {
        super(player);
        this.setFields();
        final Block b = this.getGroundBlock(player.getLocation());
        if (b == null) {
            return;
        }

        boolean disableSneak = getConfig().getBoolean("Abilities.Earth.Catapult.DisableSneak");
        if (sneak && disableSneak) return;

        this.bentBlockData = b.getBlockData();

        if (!this.bPlayer.canBend(this)) {
            return;
        }

        this.charging = sneak;
        this.sneaked = sneak;
        this.start();
    }

    public static boolean isAwaitingLanding(final Player player) {
        return AWAITING_LANDING.contains(player.getUniqueId());
    }

    private void setFields() {
        this.stageTimeMult = getConfig().getDouble("Abilities.Earth.Catapult.StageTimeMult");
        this.cooldown = getConfig().getLong("Abilities.Earth.Catapult.Cooldown");
        this.clickCd = getConfig().getLong("Abilities.Earth.Catapult.ClickCooldown");
        this.angle = Math.toRadians(getConfig().getDouble("Abilities.Earth.Catapult.Angle"));
        this.cancelWithAngle = getConfig().getBoolean("Abilities.Earth.Catapult.CancelWithAngle");
        this.removeFireTick = getConfig().getBoolean("Abilities.Earth.Catapult.RemoveFireTick");
        this.launchPower = getConfig().getDouble("Abilities.Earth.Catapult.LaunchPower");
        this.launchOthers = getConfig().getBoolean("Abilities.Earth.Catapult.LaunchOthers");
        this.playRegularSound = getConfig().getBoolean("Abilities.Earth.Catapult.RegularSound", false);
        this.activationHandled = false;
        this.stage = 1;
        this.stageStart = System.currentTimeMillis();
        this.up = new Vector(0, 1, 0);
    }

    private void moveEarth(final Vector apply, final Vector direction) {
        if (launchOthers) {
            for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.origin, 2)) {
                if (entity.getEntityId() == this.player.getEntityId()) continue;
                if (GeneralMethods.isRegionProtectedFromBuild(this, entity.getLocation()) || ((entity instanceof Player) && Commands.invincible.contains(((Player) entity).getName()))) {
                    continue;
                }
                GeneralMethods.setVelocity(this, entity, apply);
            }
        }
        if (bPlayer.areSourceHolesOn()) this.moveEarth(this.origin.clone().subtract(direction), direction, 3, false);
    }

    @Override
    public void progress() {
        if (!this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            this.remove();
            return;
        }

        final Block b = this.getGroundBlock(this.player.getLocation());
        if (b == null) {
            this.remove();
            return;
        }

        this.bentBlockData = b.getBlockData();

        if (this.charging) {
            if (this.stage == 4 || !this.player.isSneaking()) {
                this.charging = false;
            } else {
                if ((System.currentTimeMillis() - this.stageStart) >= ((Math.max(0, this.stageTimeMult * (this.stage - 1))) * 1000)) {
                    this.stage++;
                    this.stageStart = System.currentTimeMillis();
                    final Random random = new Random();
                    ParticleEffect.BLOCK_DUST.display(this.player.getLocation(), 15, random.nextFloat(), random.nextFloat(), random.nextFloat(), this.bentBlockData);
                    ParticleEffect.BLOCK_DUST.display(this.player.getLocation().add(0, 0.5, 0), 10, random.nextFloat(), random.nextFloat(), random.nextFloat(), this.bentBlockData);
                    if (playRegularSound) {
                        this.player.getWorld().playSound(this.player.getLocation(), Sound.ENTITY_GHAST_SHOOT, 1.0f, 1.0f);
                    } else {
                        playEarthbendingSound(this.player.getLocation());
                    }
                }
            }
            return;
        }

        Vector direction = null;
        if (!this.activationHandled) {
            this.origin = this.player.getLocation().clone();
            direction = this.player.getEyeLocation().getDirection().clone().normalize();

            if (!this.bPlayer.canBend(this)) {
                this.activationHandled = true;
                this.remove();
                return;
            }
            this.activationHandled = true;
            if (sneaked) {
                this.bPlayer.addCooldown(this);
            } else {
                this.bPlayer.addCooldown(this, clickCd);
            }
        }

        if (this.up.angle(this.player.getEyeLocation().getDirection()) > this.angle) {
            if (this.cancelWithAngle) {
                this.remove();
                return;
            }
            direction = this.up;
        }

        final Location tar = this.origin.clone().add(direction.clone().normalize().multiply(this.stage + launchPower));
        this.target = tar;
        final Vector apply = this.target.clone().toVector().subtract(this.origin.clone().toVector());
        GeneralMethods.setVelocity(this, this.player, apply);
        this.awaitLanding(this.player);
        this.moveEarth(apply, direction);
        if (removeFireTick) player.setFireTicks(0);
        this.remove();
    }

    private void awaitLanding(final Player player) {
        final UUID uuid = player.getUniqueId();
        AWAITING_LANDING.add(uuid);

        new BukkitRunnable() {
            private boolean airborne;
            private int ticks;

            @Override
            public void run() {
                this.ticks++;

                if (!player.isOnline() || player.isDead()) {
                    AWAITING_LANDING.remove(uuid);
                    this.cancel();
                    return;
                }

                if (!GeneralMethods.isOnGround(player)) {
                    this.airborne = true;
                    return;
                }

                if (this.airborne || this.ticks > 20) {
                    AWAITING_LANDING.remove(uuid);
                    this.cancel();
                }
            }
        }.runTaskTimer(ProjectKorra.plugin, 10L, 1L);
    }

    private Block getGroundBlock(final Location location) {
        double[] offsets = {0.0, 0.3, -0.3};
        for (double xOffset : offsets) {
            for (double zOffset : offsets) {
                Block block = location.clone().add(xOffset, 0, zOffset).getBlock().getRelative(BlockFace.DOWN, 1);
                if (isEarth(block) || isSand(block) || isMetal(block)) {
                    return block;
                }
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "Catapult";
    }

    @Override
    public Location getLocation() {
        if (this.player != null) {
            return this.player.getLocation();
        }
        return null;
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    public void setCooldown(final long cooldown) {
        this.cooldown = cooldown;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    public Location getOrigin() {
        return this.origin;
    }

    public void setOrigin(final Location origin) {
        this.origin = origin;
    }

    public Location getTarget() {
        return this.target;
    }

    public void setTarget(final Location target) {
        this.target = target;
    }
}

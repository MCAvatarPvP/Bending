package com.projectkorra.projectkorra.firebending;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.ElementalAbility;
import com.projectkorra.projectkorra.ability.FireAbility;
import com.projectkorra.projectkorra.airbending.AirSpout;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.attribute.markers.DayNightFactor;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Tag;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.TempBlock;
import com.projectkorra.projectkorra.waterbending.Torrent;

import java.util.Random;

public class FireJet extends FireAbility {

    @Attribute("AvatarStateToggle")
    private boolean avatarStateToggled;
    private long time;
    @Attribute(Attribute.DURATION)
    @DayNightFactor
    private long duration;
    @Attribute(Attribute.COOLDOWN)
    @DayNightFactor(invert = true)
    private long cooldown;
    @Attribute(Attribute.SPEED)
    @DayNightFactor
    private double speed;
    private Random random;
    private Boolean previousGlidingState;
    private Boolean showGliding;
    private int particleAmount;

    public FireJet(final Player player) {
        super(player);

        final FireJet oldJet = getAbility(player, FireJet.class);
        if (oldJet != null) {
            oldJet.remove();
            return;
        } else if (this.bPlayer.isOnCooldown(this)) {
            return;
        }

        if (hasAbility(player, AirSpout.class)) {
            final AirSpout abil = getAbility(player, AirSpout.class);
            abil.remove();
        }

        this.avatarStateToggled = getConfig().getBoolean("Abilities.Avatar.AvatarState.Fire.FireJet.IsAvatarStateToggle");
        this.duration = (long) getConfig().getLong("Abilities.Fire.FireJet.Duration");
        this.speed = getConfig().getDouble("Abilities.Fire.FireJet.Speed");
        this.cooldown = getConfig().getLong("Abilities.Fire.FireJet.Cooldown");
        this.showGliding = getConfig().getBoolean("Abilities.Fire.FireJet.ShowGliding");
        this.random = new Random();
        this.particleAmount = 10;

        final Block block = player.getLocation().getBlock();

        this.recalculateAttributes();

        if (isIgnitable(block) || ElementalAbility.isAir(block.getType()) || Tag.SLABS.isTagged(block.getType()) || this.bPlayer.isAvatarState()) {
            GeneralMethods.setVelocity(this, player, player.getEyeLocation().getDirection().clone().normalize().multiply(this.speed));
            if (!canFireGrief()) {
                if (ElementalAbility.isAir(block.getType())) {
                    createTempFire(block.getLocation());
                }
            } else if (ElementalAbility.isAir(block.getType())) {
                createTempFire(block.getLocation());
            }

            this.flightHandler.createInstance(player, this.getName());
            player.setAllowFlight(true);
            player.setFireTicks(0);
            this.time = System.currentTimeMillis();

            this.start();
            if (this.showGliding) {
                this.previousGlidingState = player.isGliding();
                player.setGliding(true);
            }
        }
    }

    @Override
    public void progress() {
        final Block playerBlock = this.player.getLocation().getBlock();
        if (this.player.isDead() || !this.player.isOnline()) {
            this.remove();
            return;
        } else if ((isWater(playerBlock) && !canPassThroughWater(playerBlock) && !isTorrentWater(playerBlock)) || System.currentTimeMillis() > this.time + this.duration) {
            final boolean durationExpired = System.currentTimeMillis() > this.time + this.duration;
            this.remove();
            if (this.isRemoved() || !durationExpired || this.duration <= 0) return;
        }
        if (this.random.nextInt(2) == 0) {
            playFirebendingSound(this.player.getLocation());
        }

        playFirebendingParticles(this.player.getLocation(), particleAmount, 0.3, 0.3, 0.3);
        emitFirebendingLight(this.player.getLocation());
        this.player.setFireTicks(0);
        double timefactor;

        if (this.bPlayer.isAvatarState() && this.avatarStateToggled) {
            timefactor = 1;
        } else {
            timefactor = 1 - (System.currentTimeMillis() - this.time) / (2.0 * this.duration);
        }

        final Vector velocity = this.player.getEyeLocation().getDirection().clone().normalize().multiply(this.speed * timefactor);
        GeneralMethods.setVelocity(this, this.player, velocity);
        this.player.setFallDistance(0);
    }

    private boolean isTorrentWater(final Block block) {
        return TempBlock.isTempBlock(block)
                && TempBlock.get(block).getAbility().orElse(null) instanceof Torrent;
    }

    @Override
    public void remove() {
        super.remove();
        if (!this.isRemoved()) return;
        if (this.showGliding) {
            this.player.setGliding(this.previousGlidingState);
        }
        this.flightHandler.removeInstance(this.player, this.getName());
        this.player.setFallDistance(0);
        this.bPlayer.addCooldown(this);
    }

    @Override
    public String getName() {
        return "FireJet";
    }

    @Override
    public Location getLocation() {
        return this.player != null ? this.player.getLocation() : null;
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
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    public boolean isAvatarStateToggled() {
        return this.avatarStateToggled;
    }

    public void setAvatarStateToggled(final boolean avatarStateToggled) {
        this.avatarStateToggled = avatarStateToggled;
    }

    public int getParticleAmount() {
        return particleAmount;
    }

    public void setParticleAmount(int particleAmount) {
        this.particleAmount = particleAmount;
    }

    public long getTime() {
        return this.time;
    }

    public void setTime(final long time) {
        this.time = time;
    }

    public long getDuration() {
        return this.duration;
    }

    public void setDuration(final long duration) {
        this.duration = duration;
    }

    public double getSpeed() {
        return this.speed;
    }

    public void setSpeed(final double speed) {
        this.speed = speed;
    }
}

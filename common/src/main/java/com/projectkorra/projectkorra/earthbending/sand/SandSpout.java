package com.projectkorra.projectkorra.earthbending.sand;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.SandAbility;
import com.projectkorra.projectkorra.ability.util.Collision;
import com.projectkorra.projectkorra.airbending.AirScooter;
import com.projectkorra.projectkorra.airbending.AirSpout;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.block.Block;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import com.projectkorra.projectkorra.util.FallHandler;
import com.projectkorra.projectkorra.waterbending.WaterSpout;

import java.util.ArrayList;
import java.util.List;

/** A carried-sand spout with familiar flight controls and a momentum exit hop. */
public class SandSpout extends SandAbility {
    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    @Attribute(Attribute.HEIGHT)
    private double height;
    private long prepareTimeout;
    private double hopForward;
    private double hopUp;
    private int visualStrands;
    private double visualSpacing;
    private int soundIntervalTicks;

    private boolean riding;
    private boolean hopped;

    public SandSpout(final Player player) {
        super(player);
        if (player == null || hasAbility(player, SandSpout.class)) return;
        this.loadFields();
        if (!this.bPlayer.canBend(this) || this.bPlayer.isOnCooldown(this)) return;
        this.start();
    }

    public static boolean activate(final Player player) {
        final SandSpout spout = getAbility(player, SandSpout.class);
        if (spout == null) return false;
        if (!spout.riding) return spout.beginRiding();
        if (player.isSneaking()) {
            spout.remove();
        } else {
            spout.hop();
        }
        return true;
    }

    private void loadFields() {
        final String path = "Abilities.Earth.SandSpout.";
        this.cooldown = getConfig().getLong(path + "Cooldown", 4000L);
        this.duration = getConfig().getLong(path + "Duration", 10000L);
        this.height = getConfig().getDouble(path + "Height", 6.0);
        this.prepareTimeout = getConfig().getLong(path + "PrepareTimeout", 3000L);
        this.hopForward = getConfig().getDouble(path + "HopForward", 0.75);
        this.hopUp = getConfig().getDouble(path + "HopUp", 0.55);
        this.visualStrands = Math.max(2, Math.min(6,
                getConfig().getInt(path + "Visuals.Strands", 3)));
        this.visualSpacing = Math.max(0.18,
                getConfig().getDouble(path + "Visuals.Spacing", 0.32));
        this.soundIntervalTicks = Math.max(4,
                getConfig().getInt(path + "Sound.IntervalTicks", 9));
    }

    private boolean beginRiding() {
        if (this.riding || !this.player.isSneaking()) return false;
        final AirScooter scooter = getAbility(this.player, AirScooter.class);
        if (scooter != null) scooter.remove();
        final AirSpout airSpout = getAbility(this.player, AirSpout.class);
        if (airSpout != null) airSpout.remove();
        final WaterSpout waterSpout = getAbility(this.player, WaterSpout.class);
        if (waterSpout != null) waterSpout.remove();
        this.riding = true;
        this.flightHandler.createInstance(this.player, this.getName());
        this.player.setAllowFlight(true);
        this.player.setFlying(true);
        this.player.setFallDistance(0);
        this.displaySandBurst(this.player.getLocation().clone().add(0, 0.25, 0),
                18, 0.65, 0.16, 0.65, 0.03, false);
        this.playSandSound(this.player.getLocation(), Sound.BLOCK_SAND_BREAK, 1.0F, 0.72F);
        this.playSandSound(this.player.getLocation(), Sound.ENTITY_BREEZE_CHARGE, 0.55F, 0.78F);
        return true;
    }

    @Override
    public void progress() {
        if (this.player.isDead() || !this.player.isOnline()
                || !this.bPlayer.canBendIgnoreBindsCooldowns(this)) {
            this.remove();
            return;
        }
        if (!this.riding) {
            if (!this.player.isSneaking() || System.currentTimeMillis() > this.getStartTime() + this.prepareTimeout) {
                this.remove();
                return;
            }
            this.renderGatheringSand();
            return;
        }
        if (this.duration > 0 && System.currentTimeMillis() > this.getStartTime() + this.duration) {
            this.remove();
            return;
        }
        final Block ground = this.findGround();
        if (ground == null || this.player.getLocation().getY() > ground.getY() + this.height + 2) {
            this.remove();
            return;
        }
        this.player.setAllowFlight(true);
        this.player.setFlying(true);
        this.player.setFallDistance(0);
        this.renderVortex(ground);
        if (this.getRunningTicks() % this.soundIntervalTicks == 0L) {
            this.playSandSound(this.player.getLocation(), Sound.BLOCK_SAND_BREAK, 0.34F, 1.22F);
            this.playSandSound(this.player.getLocation(), Sound.ENTITY_BREEZE_IDLE_GROUND, 0.24F, 0.82F);
        }
    }

    private Block findGround() {
        final Block start = this.player.getLocation().getBlock();
        for (int offset = 0; offset <= this.height + 3; offset++) {
            final Block candidate = start.getRelative(BlockFace.DOWN, offset);
            if (GeneralMethods.isSolid(candidate) && !candidate.isLiquid()) return candidate;
        }
        return null;
    }

    private void renderVortex(final Block ground) {
        final double columnHeight = Math.min(this.height,
                Math.max(1, this.player.getLocation().getY() - ground.getY()));
        final double phase = this.getRunningTicks() * 0.34;
        int sample = 0;
        for (double y = 0.18; y <= columnHeight; y += this.visualSpacing) {
            final double progress = y / columnHeight;
            final double radius = 0.68 - 0.24 * progress
                    + Math.sin(y * 2.1 + phase) * 0.06;
            for (int strand = 0; strand < this.visualStrands; strand++) {
                final double angle = phase + y * 2.35
                        + strand * Math.PI * 2.0 / this.visualStrands;
                final Location point = new Location(this.player.getWorld(),
                        this.player.getLocation().getX(), ground.getY() + y,
                        this.player.getLocation().getZ());
                point.add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
                this.displayFineSand(point, 1, 0.015, 0.025, 0.015, 0.0, false);
                if ((sample++ + this.getRunningTicks()) % 11L == 0L) {
                    this.displaySandBurst(point, 1, 0.035, 0.035, 0.035, 0.005, false);
                }
            }
        }

        final Location base = new Location(this.player.getWorld(), this.player.getLocation().getX(),
                ground.getY() + 0.12, this.player.getLocation().getZ());
        for (int index = 0; index < 12; index++) {
            final double angle = phase * 0.65 + index * Math.PI * 2.0 / 12.0;
            final double radius = 0.38 + (index % 3) * 0.18;
            this.displayFineSand(base.clone().add(Math.cos(angle) * radius, 0,
                    Math.sin(angle) * radius), 1, 0.03, 0.02, 0.03, 0.0, false);
        }
    }

    private void renderGatheringSand() {
        final Location base = this.player.getLocation().clone().add(0, 0.12, 0);
        final double phase = this.getRunningTicks() * 0.42;
        for (int index = 0; index < 14; index++) {
            final double angle = phase + index * Math.PI * 2.0 / 14.0;
            final double radius = 0.35 + (index % 4) * 0.12;
            final double y = 0.04 + (index % 5) * 0.08;
            this.displayFineSand(base.clone().add(Math.cos(angle) * radius, y,
                    Math.sin(angle) * radius), 1, 0.02, 0.02, 0.02, 0.0, false);
        }
        if (this.getRunningTicks() % 10L == 0L) {
            this.playSandSound(base, Sound.BLOCK_SAND_BREAK, 0.25F, 1.35F);
        }
    }

    private void hop() {
        final Vector retained = this.player.getVelocity().clone().setY(0);
        final Vector aimed = this.player.getEyeLocation().getDirection().clone().normalize().multiply(this.hopForward);
        final Vector launch = retained.add(aimed);
        launch.setY(Math.max(this.hopUp, launch.getY()));
        this.hopped = true;
        GeneralMethods.setVelocity(this, this.player, launch);
        this.displaySandBurst(this.player.getLocation().clone().add(0, 0.45, 0),
                22, 0.75, 0.32, 0.75, 0.05, false);
        this.playSandSound(this.player.getLocation(), Sound.ENTITY_BREEZE_SHOOT, 0.75F, 0.82F);
        this.playSandSound(this.player.getLocation(), Sound.BLOCK_SAND_BREAK, 0.9F, 0.64F);
        this.remove();
    }

    @Override
    public void handleCollision(final Collision collision) {
        if (collision.isRemovingFirst()) this.bPlayer.addCooldown(this);
        super.handleCollision(collision);
    }

    @Override
    public List<Location> getLocations() {
        final List<Location> locations = new ArrayList<>();
        if (!this.riding) return locations;
        final Block ground = this.findGround();
        if (ground == null) return locations;
        for (double y = ground.getY() + 0.5; y <= this.player.getLocation().getY(); y += 0.5) {
            locations.add(new Location(this.player.getWorld(), this.player.getLocation().getX(), y,
                    this.player.getLocation().getZ()));
        }
        return locations;
    }

    @Override
    public double getCollisionRadius() {
        return 0.5;
    }

    @Override
    public void remove() {
        super.remove();
        if (!this.isRemoved()) return;
        if (this.riding) {
            this.flightHandler.removeInstance(this.player, this.getName());
            FallHandler.stopFall(this.player, false);
            this.bPlayer.addCooldown(this);
        }
    }

    @Override
    public String getName() {
        return "SandSpout";
    }

    @Override
    public Location getLocation() {
        return this.player == null ? null : this.player.getLocation();
    }

    @Override
    public long getCooldown() {
        return this.cooldown;
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
    }

    public boolean isRiding() {
        return this.riding;
    }

    public boolean hasHopped() {
        return this.hopped;
    }

}

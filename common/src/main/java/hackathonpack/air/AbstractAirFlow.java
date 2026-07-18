package hackathonpack.air;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ProjectKorra;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.scheduler.BukkitRunnable;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

import java.util.ArrayList;

abstract class AbstractAirFlow extends AirAbility {
    private final ArrayList<Location> particles = new ArrayList<>();
    private final ArrayList<Entity> affectedEntities = new ArrayList<>();
    private long chargeTime;
    private long launchTime;
    private long duration;
    private double range;
    private int particlePerTick;
    private double particleSpawnAreaSize;
    private Location flowLocation;
    private Vector flowDirection;
    private State state = State.CHARGING;
    private boolean draining;

    protected AbstractAirFlow(final Player player) {
        super(player);
    }

    protected void setFlowFields(final long chargeTime, final long duration, final double range, final int particlePerTick, final double particleSpawnAreaSize) {
        this.chargeTime = chargeTime;
        this.duration = duration;
        this.range = range;
        this.particlePerTick = particlePerTick;
        this.particleSpawnAreaSize = particleSpawnAreaSize;
    }

    protected abstract int sourceDistance();

    protected abstract double directionMultiplier();

    @Override
    public void progress() {
        if (this.state == State.LAUNCHED && System.currentTimeMillis() > this.launchTime + this.duration) {
            remove();
            return;
        }
        if (this.state == State.CHARGING) {
            if (!this.player.isSneaking()) {
                remove();
            } else if (System.currentTimeMillis() > getStartTime() + this.chargeTime) {
                this.state = State.CHARGED;
            } else if (Math.random() < 0.2) {
                playAirbendingParticles(this.bPlayer, this.player.getEyeLocation().add(this.player.getLocation().getDirection().multiply(sourceDistance())), 1);
            }
            return;
        }
        if (this.state == State.CHARGED) {
            if (!this.player.isSneaking()) {
                this.state = State.LAUNCHED;
                this.launchTime = System.currentTimeMillis();
                this.flowLocation = this.player.getEyeLocation().add(this.player.getLocation().getDirection().multiply(sourceDistance()));
                this.flowDirection = this.player.getLocation().getDirection().multiply(directionMultiplier());
                this.player.getWorld().playSound(this.flowLocation, Sound.valueOf("ITEM_ELYTRA_FLYING"), 0.05F, 1);
                this.bPlayer.addCooldown(this);
            } else {
                playAirbendingParticles(this.bPlayer, this.player.getEyeLocation().add(this.player.getLocation().getDirection().multiply(sourceDistance())), 1);
            }
            return;
        }
        tickParticles(true);
        if (Math.random() < 0.05)
            this.player.getWorld().playSound(this.flowLocation, Sound.valueOf("ITEM_ELYTRA_FLYING"), 0.05F, 1);
    }

    private void tickParticles(final boolean emitParticles) {
        if (emitParticles) {
            for (int i = 0; i < this.particlePerTick; i++) {
                this.particles.add(this.flowLocation.clone().add(rotate(new Vector(Math.random() * this.particleSpawnAreaSize - this.particleSpawnAreaSize / 2,
                        Math.random() * this.particleSpawnAreaSize - this.particleSpawnAreaSize / 2, 0))));
            }
        }
        this.affectedEntities.clear();
        for (int i = this.particles.size() - 1; i >= 0; i--) {
            final Location loc = this.particles.get(i);
            playAirbendingParticles(this.bPlayer, loc, 1);
            final Vector velocity = this.flowDirection.clone().rotateAroundY(Math.toRadians(noise(loc) * 90)).normalize();
            for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(loc, 2)) {
                if (!this.affectedEntities.contains(entity)) {
                    GeneralMethods.setVelocity(entity, velocity.clone().add(entity.getVelocity()).multiply(0.5));
                    this.affectedEntities.add(entity);
                }
            }
            loc.add(velocity.multiply(0.5));
            if (loc.distance(this.flowLocation) > this.range || GeneralMethods.isSolid(loc.getBlock())) {
                this.particles.remove(i);
            }
        }
    }

    private double noise(final Location location) {
        return Math.sin(location.getX() * 0.31 + location.getY() * 0.17 + location.getZ() * 0.23);
    }

    private Vector rotate(final Vector vector) {
        return hackathonpack.UtilityMethods.rotate(vector, this.flowLocation);
    }

    @Override
    public void remove() {
        super.remove();
        if (this.draining || this.player == null || this.flowLocation == null
                || this.flowDirection == null || this.particles.isEmpty())
            return;
        this.draining = true;
        new BukkitRunnable() {
            @Override
            public void run() {
                if (particles.isEmpty()) {
                    cancel();
                    return;
                }
                tickParticles(false);
            }
        }.runTaskTimer(ProjectKorra.plugin, 0, 1);
    }

    private enum State {CHARGING, CHARGED, LAUNCHED}
}

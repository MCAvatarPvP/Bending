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
    private final ArrayList<FlowParticle> particles = new ArrayList<>();
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
    private final AirFlowPath path = new AirFlowPath();

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

    protected final void launch() {
        this.state = State.LAUNCHED;
        this.launchTime = System.currentTimeMillis();
        this.flowLocation = this.player.getEyeLocation().add(this.player.getLocation().getDirection().multiply(sourceDistance()));
        this.flowDirection = this.player.getLocation().getDirection().multiply(directionMultiplier());
        this.player.getWorld().playSound(this.flowLocation, Sound.valueOf("ITEM_ELYTRA_FLYING"), 0.05F, 1);
        this.bPlayer.addCooldown(this);
    }

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
                launch();
            } else {
                playAirbendingParticles(this.bPlayer, this.player.getEyeLocation().add(this.player.getLocation().getDirection().multiply(sourceDistance())), 1);
            }
            return;
        }
        if (!this.path.isFull(this.range)) {
            final Location aim = this.player.getEyeLocation();
            this.flowDirection = aim.getDirection().multiply(directionMultiplier());
            this.path.extend(this.flowDirection, this.range);
        }
        tickParticles(true);
        if (Math.random() < 0.05)
            this.player.getWorld().playSound(this.flowLocation, Sound.valueOf("ITEM_ELYTRA_FLYING"), 0.05F, 1);
    }

    private void tickParticles(final boolean emitParticles) {
        if (emitParticles) {
            for (int i = 0; i < this.particlePerTick; i++) {
                this.particles.add(new FlowParticle(this.flowLocation.clone().add(rotate(new Vector(Math.random() * this.particleSpawnAreaSize - this.particleSpawnAreaSize / 2,
                        Math.random() * this.particleSpawnAreaSize - this.particleSpawnAreaSize / 2, 0)))));
            }
        }
        this.affectedEntities.clear();
        for (int i = this.particles.size() - 1; i >= 0; i--) {
            final FlowParticle particle = this.particles.get(i);
            if (particle.segment >= this.path.size()) {
                this.particles.remove(i);
                continue;
            }
            final Location loc = particle.location;
            final Vector step = this.path.step(particle.segment++);
            playAirbendingParticles(this.bPlayer, loc, 1);
            final Vector velocity = step.clone().normalize();
            for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(loc, 2)) {
                if (!this.affectedEntities.contains(entity)) {
                    GeneralMethods.setVelocity(this, entity, velocity.clone().add(entity.getVelocity()).multiply(0.5));
                    this.affectedEntities.add(entity);
                }
            }
            loc.add(step);
            if ((this.path.isFull(this.range) && particle.segment >= this.path.size())
                    || GeneralMethods.isSolid(loc.getBlock())) {
                this.particles.remove(i);
            }
        }
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

    private static final class FlowParticle {
        private final Location location;
        private int segment;

        private FlowParticle(final Location location) {
            this.location = location;
        }
    }

    private enum State {CHARGING, CHARGED, LAUNCHED}
}

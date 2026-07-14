package hackathonpack.air;

import com.projectkorra.projectkorra.BendingPlayer;
import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AirAbility;
import com.projectkorra.projectkorra.configuration.ConfigManager;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.entity.Entity;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;

public class AirSpinBlast {
    private final BendingPlayer bPlayer;
    private final LivingEntity target;
    private final double blastSpeed = 0.05;
    private Location blastLocation;
    private Vector blastDirection;
    private boolean reached;

    public AirSpinBlast(final Player source, final LivingEntity destination) {
        AirAbility.playAirbendingSound(source.getLocation());
        this.bPlayer = BendingPlayer.getBendingPlayer(source);
        this.target = destination;
        this.blastLocation = source.getLocation().clone().add(0, 1, 0);
        this.blastDirection = destination.getLocation().clone().add(0, 1, 0).toVector().subtract(source.getLocation().toVector()).normalize();
    }

    public void update() {
        final Vector current = this.blastDirection.clone().multiply(this.blastSpeed);
        final Vector desired = this.target.getLocation().clone().add(0, 1, 0).toVector().subtract(this.blastLocation.toVector());
        this.blastDirection = this.blastDirection.add(desired.subtract(current).multiply(0.75));
        this.blastLocation.add(this.blastDirection.clone().multiply(this.blastSpeed));
        if (!this.blastLocation.getBlock().isEmpty()) {
            this.reached = true;
        }
        for (final Entity entity : GeneralMethods.getEntitiesAroundPoint(this.blastLocation, 2)) {
            if (entity.equals(this.target)) {
                this.reached = true;
                spin();
            }
        }
    }

    public void show() {
        AirAbility.playAirbendingParticles(this.bPlayer, this.blastLocation, ConfigManager.getConfig().getInt("Abilities.Air.AirBlast.Particles"), 0.275F, 0.275F, 0.275F);
    }

    public void spin() {
        final Vector velocity = this.target.getVelocity();
        this.target.teleport(this.target.getLocation().setDirection(this.target.getLocation().getDirection().multiply(-1)));
        this.target.setVelocity(velocity.multiply(-1));
        for (double y = 0; y <= 2; y += 0.05) {
            AirAbility.playAirbendingParticles(this.bPlayer, this.target.getLocation().clone().add(Math.cos(y * 20), y - 1, Math.sin(y * 20)), 1);
        }
    }

    public boolean isReached() {
        return this.reached;
    }
}

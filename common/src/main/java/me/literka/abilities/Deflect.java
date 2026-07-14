package me.literka.abilities;

import com.projectkorra.projectkorra.GeneralMethods;
import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.Sound;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.entity.Projectile;
import com.projectkorra.projectkorra.platform.mc.event.entity.ProjectileHitEvent;
import me.literka.ChiRework;
import me.literka.util.ParticleEffect;

public class Deflect extends ChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;
    private double deflectPower;

    public Deflect(Player player) {
        super(player);

        cooldown = ChiRework.config().getLong("Abilities.Deflect.Cooldown");
        duration = ChiRework.config().getLong("Abilities.Deflect.Duration");
        deflectPower = ChiRework.config().getLong("Abilities.Deflect.Power");

        start();
    }

    @Override
    public void progress() {
        if (player.isDead() || !player.isOnline()) {
            remove();
            return;
        }

        if (!bPlayer.canBendIgnoreCooldowns(this)) {
            bPlayer.addCooldown(this);
            remove();
            return;
        }

        if (duration != 0 && System.currentTimeMillis() > getStartTime() + duration) {
            bPlayer.addCooldown(this);
            remove();
        }
    }

    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        Location loc = projectile.getLocation();
        new ParticleEffect().type(Particle.CLOUD)
                .location(loc)
                .count(3)
                .offset(0.1, 0.1, 0.1)
                .speed(0)
                .spawn();
        loc.getWorld().playSound(loc, Sound.ENTITY_ARROW_HIT_PLAYER, 0.4f, 0.6f);
        GeneralMethods.setVelocity(this, projectile, projectile.getVelocity().multiply(-deflectPower));
//		projectile.setVelocity(projectile.getVelocity().multiply(-deflectPower));
        bPlayer.addCooldown(this);
        remove();
        event.setCancelled(true);
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return ChiRework.config().getBoolean("Abilities.Deflect.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Deflect";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.Deflect.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.Deflect.Instructions");
    }

    @Override
    public Location getLocation() {
        return player.getLocation();
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return ChiRework.authors;
    }

    @Override
    public String getVersion() {
        return ChiRework.name + " " + ChiRework.version;
    }

    @Override
    public boolean isModern() {
        return true;
    }
}
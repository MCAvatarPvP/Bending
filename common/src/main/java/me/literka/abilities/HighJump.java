package me.literka.abilities;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.block.BlockFace;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import me.literka.ChiRework;
import me.literka.util.ParticleEffect;

public class HighJump extends ChiAbility implements AddonAbility {

    private boolean enabled;
    private long cooldown;
    private double height;
    private double distance;
    private boolean extinguishFire;

    private boolean directionBasedVel;
    private Location location;

    public HighJump(Player player, boolean sneak) {
        super(player);

        if (!bPlayer.canBend(this)) return;

        if (sneak) {
            loadConfigSneak();
        } else {
            loadConfigSwing();
        }

        if (!enabled) return;

        location = player.getLocation();
        Vector velocity = directionBasedVel ? location.getDirection().normalize().multiply(distance) : player.getVelocity();
        velocity.setY(height);
        player.setVelocity(velocity);
        if (extinguishFire) player.setFireTicks(0);

        new ParticleEffect().type(Particle.CRIT).location(location).count(20).offset(0.5, 1, 0.5).speed(0.5).spawn();
        new ParticleEffect().type(Particle.CLOUD).location(location).count(30).offset(0.5, 1, 0.5).speed(0.002).spawn();

        bPlayer.addCooldown(this);
    }

    public void loadConfigSneak() {
        if (player.getLocation().getBlock().getRelative(BlockFace.DOWN).isSolid()) {
            directionBasedVel = true;
            enabled = ChiRework.config().getBoolean("Abilities.HighJump.Evade.Enabled");
            cooldown = ChiRework.config().getLong("Abilities.HighJump.Evade.Cooldown");
            height = ChiRework.config().getDouble("Abilities.HighJump.Evade.Height");
            distance = -ChiRework.config().getDouble("Abilities.HighJump.Evade.Distance");
            extinguishFire = ChiRework.config().getBoolean("Abilities.HighJump.Evade.ExtinguishFireTick");
            return;
        }

        enabled = ChiRework.config().getBoolean("Abilities.HighJump.DoubleJump.Enabled");
        cooldown = ChiRework.config().getLong("Abilities.HighJump.DoubleJump.Cooldown");
        height = ChiRework.config().getDouble("Abilities.HighJump.DoubleJump.Height");
        extinguishFire = ChiRework.config().getBoolean("Abilities.HighJump.DoubleJump.ExtinguishFireTick");
    }

    public void loadConfigSwing() {
        if (player.isSprinting()) {
            directionBasedVel = true;
            enabled = ChiRework.config().getBoolean("Abilities.HighJump.Lunge.Enabled");
            cooldown = ChiRework.config().getLong("Abilities.HighJump.Lunge.Cooldown");
            height = ChiRework.config().getDouble("Abilities.HighJump.Lunge.Height");
            distance = ChiRework.config().getDouble("Abilities.HighJump.Lunge.Distance");
            extinguishFire = ChiRework.config().getBoolean("Abilities.HighJump.Lunge.ExtinguishFireTick");
            return;
        }

        enabled = ChiRework.config().getBoolean("Abilities.HighJump.Jump.Enabled");
        cooldown = ChiRework.config().getLong("Abilities.HighJump.Jump.Cooldown");
        height = ChiRework.config().getDouble("Abilities.HighJump.Jump.Height");
        extinguishFire = ChiRework.config().getBoolean("Abilities.HighJump.Jump.ExtinguishFireTick");
    }

    @Override
    public void progress() {
    }

    @Override
    public boolean isSneakAbility() {
        return true;
    }

    @Override
    public boolean isHarmlessAbility() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return ChiRework.config().getBoolean("Abilities.HighJump.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "HighJump";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.HighJump.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.HighJump.Instructions");
    }

    @Override
    public Location getLocation() {
        return location;
    }

    @Override
    public void load() {
    }

    @Override
    public void stop() {
    }

    @Override
    public String getAuthor() {
        return "Numin (Original Creator), " + ChiRework.authors;
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
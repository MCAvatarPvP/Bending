package me.literka.abilities;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.ability.ChiAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.util.Vector;
import me.literka.ChiRework;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

public class Backstab extends ChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DAMAGE)
    private double damage;
    @Attribute("BlockChance")
    private double chance;
    private long blockDuration;
    private double maxAngle;

    private ParticleEffect effect;
    private LivingEntity target;
    private boolean cancel;

    public Backstab(Player player, LivingEntity target) {
        super(player);

        cooldown = ChiRework.config().getLong("Abilities.Backstab.Cooldown");
        damage = ChiRework.config().getDouble("Abilities.Backstab.Damage");
        chance = ChiRework.config().getDouble("Abilities.Backstab.BlockChance");
        blockDuration = ChiRework.config().getLong("Abilities.Backstab.BlockDuration");
        maxAngle = Math.toRadians(ChiRework.config().getInt("Abilities.Backstab.MaxActivationAngle"));

        effect = new ParticleEffect().type(Particle.CRIT).count(1).speed(0);
        this.target = target;

        Vector targetDirection = target.getLocation().getDirection().setY(0).normalize();
        Vector toTarget = target.getLocation().toVector().subtract(player.getLocation().toVector()).normalize();

        double angle = toTarget.clone().setY(0).angle(targetDirection);

        if (angle <= maxAngle && target.getLocation().distanceSquared(player.getLocation()) <= 5 * 5) {
            Utils.damage(target, damage, this);
            Utils.punchSound(target.getLocation(), true);
            bPlayer.addCooldown(this);

            if (target instanceof Player p && Utils.willChiBlock(p, chance)) {
                Utils.blockChi(p, blockDuration);
            }

            Location location = target.getBoundingBox().getCenter().toLocation(target.getWorld());
            Vector v = Utils.getOrthogonalVector(toTarget, location.getYaw(), 0, 0.3);

            double dist = player.getLocation().distance(target.getLocation());
            Vector multiply = toTarget.clone().multiply(dist);
            location.add(multiply.clone().multiply(0.9));
            Location pLoc = location.clone().subtract(multiply.multiply(1.5));
            Location left = pLoc.clone().add(v);
            Location right = pLoc.clone().subtract(v);
            for (Location l : Utils.bezier(left, location, right)) {
                effect.location(l).spawn();
            }

            cancel = true;
        }
    }

    public boolean shouldCancel() {
        return cancel;
    }

    @Override
    public void progress() {
    }

    @Override
    public boolean isSneakAbility() {
        return false;
    }

    @Override
    public boolean isHarmlessAbility() {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return ChiRework.config().getBoolean("Abilities.Backstab.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Backstab";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.Backstab.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.Backstab.Instructions");
    }

    @Override
    public Location getLocation() {
        return target.getLocation();
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
}

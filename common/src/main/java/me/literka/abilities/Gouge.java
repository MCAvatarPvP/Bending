package me.literka.abilities;

import com.projectkorra.projectkorra.ability.AddonAbility;
import com.projectkorra.projectkorra.attribute.Attribute;
import com.projectkorra.projectkorra.platform.mc.Location;
import com.projectkorra.projectkorra.platform.mc.Particle;
import com.projectkorra.projectkorra.platform.mc.entity.LivingEntity;
import com.projectkorra.projectkorra.platform.mc.entity.Player;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffect;
import com.projectkorra.projectkorra.platform.mc.potion.PotionEffectType;
import me.literka.ChiRework;
import me.literka.ModernChiAbility;
import me.literka.util.ParticleEffect;
import me.literka.util.Utils;

public class Gouge extends ModernChiAbility implements AddonAbility {

    @Attribute(Attribute.COOLDOWN)
    private long cooldown;
    @Attribute(Attribute.DURATION)
    private long duration;

    private double damage;

    private LivingEntity target;

    public Gouge(Player player, LivingEntity target) {
        super(player);

        double minDistance = ChiRework.config().getDouble("Abilities.Gouge.MinHitDistance");
        if (player.getLocation().distanceSquared(target.getLocation()) > minDistance * minDistance) return;

        cooldown = ChiRework.config().getLong("Abilities.Gouge.Cooldown");
        duration = ChiRework.config().getLong("Abilities.Gouge.Duration");
        damage = ChiRework.config().getDouble("Abilities.Gouge.Damage");

        this.target = target;
        Location eyeLocation = target.getEyeLocation();
        eyeLocation.add(eyeLocation.getDirection().multiply(0.2));
        new ParticleEffect().type(Particle.SQUID_INK).location(eyeLocation).count(2).offset(0.1, 0.1, 0.1).speed(0.03).spawn();
        target.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, (int) (duration / 50 + 1), 0, false, false, false));

        bPlayer.addCooldown(this);
        start();
    }

    @Override
    public void progress() {
        if (target.isDead() || ((target instanceof Player t) && !t.isOnline())) {
            remove();
            return;
        }

        if (System.currentTimeMillis() > getStartTime() + duration) {
            target.removePotionEffect(PotionEffectType.BLINDNESS);
            remove();
        }

        if (damage != 0) {
            Utils.damage(target, damage, this);
            remove();
        }
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
        return ChiRework.config().getBoolean("Abilities.Gouge.Enabled");
    }

    @Override
    public long getCooldown() {
        return cooldown;
    }

    @Override
    public String getName() {
        return "Gouge";
    }

    @Override
    public String getDescription() {
        return ChiRework.config().getString("Language.Gouge.Description");
    }

    @Override
    public String getInstructions() {
        return ChiRework.config().getString("Language.Gouge.Instructions");
    }

    @Override
    public Location getLocation() {
        return null;
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
        return ChiRework.version;
    }
}
